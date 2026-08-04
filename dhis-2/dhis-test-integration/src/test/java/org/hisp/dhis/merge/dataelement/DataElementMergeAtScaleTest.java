/*
 * Copyright (c) 2004-2022, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors 
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.merge.dataelement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.common.UID;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dataset.DataSet;
import org.hisp.dhis.dataset.DataSetElement;
import org.hisp.dhis.feedback.ConflictException;
import org.hisp.dhis.feedback.MergeReport;
import org.hisp.dhis.merge.DataMergeStrategy;
import org.hisp.dhis.merge.MergeParams;
import org.hisp.dhis.merge.MergeService;
import org.hisp.dhis.period.PeriodType;
import org.hisp.dhis.period.PeriodTypeEnum;
import org.hisp.dhis.test.integration.PostgresIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The regression guard for the failure mode that closed PR #24380: turning on Hibernate batch
 * fetching silently dropped one association per merge, with {@code 200 OK} and nothing logged.
 *
 * <p>That corruption needed three things together — an entity whose {@code equals}/{@code hashCode}
 * are content-based over a <em>mutable</em> field, a merge handler that mutates that field in place
 * while the entity is a managed {@code Set} member, and a change in proxy-initialisation order.
 * Batch fetching supplies the third.
 *
 * <p>{@link DataSetElement} meets the first two with respect to {@link DataElement}:
 *
 * <ul>
 *   <li>{@code DataSetElement.hashCode()} is {@code Objects.hash(super.hashCode(), dataSet,
 *       dataElement)} — content-based over the mutable {@code dataElement} field.
 *   <li>{@code MetadataDataElementMergeHandler.handleDataSetElement} calls {@code
 *       dse.setDataElement(target)} on instances that are members of {@code
 *       DataSet.dataSetElements}.
 * </ul>
 *
 * <p>So putting {@code batch-size} on the {@code DataElement} class mapping perturbs exactly the
 * variable that flipped #24380, over a structure that has the same shape as the one it corrupted.
 * The existing {@code DataElementMergeServiceTest} coverage of this path uses four data sets, which
 * is too few for batching to change anything — the same reason {@code
 * CategoryOptionMergeServiceTest} stayed green while the e2e test caught the #24380 drop.
 *
 * <p>This test therefore runs the merge over enough data sets to span several batches, and counts
 * the surviving associations <em>in the {@code datasetelement} table</em> rather than in the object
 * graph, which is what distinguished real corruption from a cache artefact in #24380.
 */
@Transactional
class DataElementMergeAtScaleTest extends PostgresIntegrationTestBase {

  /** Spans several batches at {@code batch-size="100"}; #24380 dropped exactly one association. */
  private static final int DATA_SETS_PER_SOURCE =
      Integer.getInteger("merge.dataSetsPerSource", 120);

  @Autowired private IdentifiableObjectManager manager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MergeService dataElementMergeService;

  @Test
  @DisplayName("every DataSetElement association survives a DataElement merge at batch scale")
  void dataSetElementAssociationsSurviveMergeAtScale() throws ConflictException {
    DataElement source1 = dataElement("mrgsource01");
    DataElement source2 = dataElement("mrgsource02");
    DataElement target = dataElement("mrgtarget01");
    DataElement bystander = dataElement("mrgbystand1");
    for (DataElement de : List.of(source1, source2, target, bystander)) {
      manager.save(de);
    }

    int expectedForTarget = 0;

    for (int i = 0; i < DATA_SETS_PER_SOURCE; i++) {
      // Round-robin across the two sources, the target and an untouched bystander, so the merge
      // has to move some associations, leave others alone, and not collide on the way.
      DataElement owner =
          switch (i % 4) {
            case 0 -> source1;
            case 1 -> source2;
            case 2 -> target;
            default -> bystander;
          };
      if (owner != bystander) {
        expectedForTarget++;
      }

      DataSet ds = createDataSet('X', PeriodType.getPeriodType(PeriodTypeEnum.DAILY));
      ds.setAutoFields();
      ds.setUid(String.format("mrgds%06d", i));
      ds.setName("MergeScaleDataSet" + i);
      ds.setShortName("MSDS" + i);
      ds.setCode("MSDS" + i);

      DataSetElement dse = new DataSetElement(ds, owner);
      ds.addDataSetElement(dse);
      owner.getDataSetElements().add(dse);

      manager.save(ds);
    }

    manager.flush();
    manager.clear();

    long rowsBefore = countDataSetElements();
    assertEquals(
        DATA_SETS_PER_SOURCE, rowsBefore, "fixture must produce one dataSetElement per data set");

    MergeParams params = new MergeParams();
    params.setSources(UID.of(List.of(source1.getUid(), source2.getUid())));
    params.setTarget(UID.of(target.getUid()));
    params.setDataMergeStrategy(DataMergeStrategy.LAST_UPDATED);

    MergeReport report = dataElementMergeService.processMerge(params);
    manager.flush();
    manager.clear();

    assertFalse(report.hasErrorMessages(), () -> "merge reported errors: " + report);

    long rowsAfter = countDataSetElements();
    long forTarget = countDataSetElementsFor(target.getUid());
    long forSources =
        countDataSetElementsFor(source1.getUid()) + countDataSetElementsFor(source2.getUid());

    System.out.printf(
        "[UGANDA merge scale] dataSets=%d rowsBefore=%d rowsAfter=%d forTarget=%d (expected %d) forSources=%d%n",
        DATA_SETS_PER_SOURCE, rowsBefore, rowsAfter, forTarget, expectedForTarget, forSources);

    // The three claims that #24380 violated: nothing vanished, everything landed on the target,
    // and nothing was left pointing at a source.
    assertEquals(
        rowsBefore,
        rowsAfter,
        "a merge must not change the number of dataSetElement rows — #24380 silently lost one");
    assertEquals(
        expectedForTarget,
        forTarget,
        "every association owned by a source or already owned by the target must point at the "
            + "target after the merge");
    assertEquals(0, forSources, "no association may still point at a merged-away source");
  }

  private DataElement dataElement(String uid) {
    DataElement de = createDataElement('A');
    de.setAutoFields();
    de.setUid(uid);
    de.setName("MergeScale-" + uid);
    de.setShortName("MS-" + uid);
    de.setCode("MS-" + uid);
    return de;
  }

  private long countDataSetElements() {
    Long n =
        jdbcTemplate.queryForObject(
            "select count(*) from datasetelement dse "
                + "join dataelement de on de.dataelementid = dse.dataelementid "
                + "where de.uid like 'mrg%'",
            Long.class);
    return n == null ? 0 : n;
  }

  private long countDataSetElementsFor(String dataElementUid) {
    Long n =
        jdbcTemplate.queryForObject(
            "select count(*) from datasetelement dse "
                + "join dataelement de on de.dataelementid = dse.dataelementid "
                + "where de.uid = ?",
            Long.class,
            dataElementUid);
    return n == null ? 0 : n;
  }
}
