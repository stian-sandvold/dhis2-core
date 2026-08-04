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
package org.hisp.dhis.dataelement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.hisp.dhis.category.CategoryCombo;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.program.ProgramStageDataElement;
import org.hisp.dhis.test.integration.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reproduces the {@code select ... from dataelement where dataelementid=?} N+1 that the Uganda
 * Glowroot traces show at 951 executions on every tracked-entity analytics request (UGANDA-10, F7 /
 * F13), and the sibling {@code categorycombo} by-primary-key loop.
 *
 * <p>The shape is a to-one proxy N+1: a collection of owning rows is loaded with one query, which
 * hydrates one uninitialised {@code DataElement} proxy per row, and something then dereferences
 * every proxy in turn — {@link ProgramStage#getDataElements()} is one such caller, reached from
 * analytics dimension resolution. Each dereference is a separate single-row {@code SELECT}.
 *
 * <p>Hibernate can coalesce those into {@code IN (...)} batches, but only when the proxies are
 * registered as batch-loadable, which {@code AbstractEntityPersister.isBatchLoadable()} gates on
 * {@code batch-size &gt; 1} in the mapping. The assertions below are therefore written as a
 * <em>shape</em> claim — dereferencing N proxies must not cost N queries — so the test fails on a
 * mapping without {@code batch-size} and passes with it.
 *
 * <p>This is deliberately not the same mechanism as an L2-cached {@code <set>} assembling its
 * elements one at a time ({@code PersistentSet.initializeFromCache}), which batch fetching provably
 * cannot help because no proxy exists until its predecessor has been dereferenced. Here every proxy
 * is created up front during hydration of the owning rows, so the batch-fetch queue is populated
 * before the first dereference.
 */
@Transactional
class DataElementProxyBatchFetchTest extends PostgresIntegrationTestBase {

  /** Uganda's traces show 951 data elements. Committed smaller so the suite stays quick. */
  private static final int DATA_ELEMENTS = Integer.getInteger("batchfetch.dataElements", 300);

  /** Distinct category combos the data elements point at, round-robin. */
  private static final int CATEGORY_COMBOS = Integer.getInteger("batchfetch.categoryCombos", 60);

  /** The value in the mapping under test. */
  private static final int BATCH_SIZE = 100;

  @Autowired private IdentifiableObjectManager manager;
  @Autowired private EntityManager em;

  private ProgramStage programStage;

  @BeforeEach
  void setUp() {
    List<CategoryCombo> combos = new ArrayList<>();
    for (int i = 0; i < CATEGORY_COMBOS; i++) {
      CategoryCombo combo = createCategoryCombo("bf" + i);
      manager.save(combo);
      combos.add(combo);
    }

    Program program = createProgram('B');
    manager.save(program);

    programStage = createProgramStage('B', program);
    programStage.setProgram(program);

    for (int i = 0; i < DATA_ELEMENTS; i++) {
      DataElement de = createDataElement('A');
      de.setAutoFields();
      de.setUid(String.format("bfde%06d", i));
      de.setName("BatchFetchDataElement" + i);
      de.setShortName("BFDE" + i);
      de.setCode("BFDE" + i);
      de.setCategoryCombo(combos.get(i % CATEGORY_COMBOS));
      manager.save(de);

      programStage
          .getProgramStageDataElements()
          .add(createProgramStageDataElement(programStage, de, i + 1));
    }

    manager.save(programStage);

    manager.flush();
    manager.clear();
  }

  private Statistics statistics() {
    Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
    stats.setStatisticsEnabled(true);
    return stats;
  }

  /** Ceiling of the batched query count for {@code n} proxies, plus slack for the owning query. */
  private static long batchedQueryCeiling(int n) {
    return (long) Math.ceil((double) n / BATCH_SIZE) + 2;
  }

  @Test
  @DisplayName(
      "dereferencing every ProgramStageDataElement.dataElement must not cost one query each")
  void dataElementProxiesAreBatchFetched() {
    Statistics stats = statistics();

    // One query loads every ProgramStageDataElement of the stage, hydrating one uninitialised
    // DataElement proxy per row — the state the traced request was in.
    List<ProgramStageDataElement> psdes =
        em.createQuery(
                "from ProgramStageDataElement psde where psde.programStage.uid = :uid",
                ProgramStageDataElement.class)
            .setParameter("uid", programStage.getUid())
            .getResultList();
    assertEquals(DATA_ELEMENTS, psdes.size(), "fixture must load every programStageDataElement");

    long queriesBefore = stats.getPrepareStatementCount();
    long loadsBefore = stats.getEntityLoadCount();
    long startedAt = System.nanoTime();

    Set<String> resolved = new HashSet<>();
    int touched = 0;
    for (ProgramStageDataElement psde : psdes) {
      // getValueType() is what DimensionsServiceCommon's value-type getter reads.
      DataElement de = psde.getDataElement();
      if (de.getValueType() != null) {
        touched++;
      }
      resolved.add(de.getUid());
    }

    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
    long queries = stats.getPrepareStatementCount() - queriesBefore;
    long entityLoads = stats.getEntityLoadCount() - loadsBefore;

    System.out.printf(
        "[UGANDA batch-fetch] dataElements=%d dereferenced=%d distinct=%d queries=%d entityLoads=%d ms=%d%n",
        DATA_ELEMENTS, touched, resolved.size(), queries, entityLoads, elapsedMs);

    assertEquals(DATA_ELEMENTS, touched, "every data element must have been dereferenced");
    // Batching may only change how the rows are fetched, never which entity a proxy resolves to.
    Set<String> expected = new HashSet<>();
    for (int i = 0; i < DATA_ELEMENTS; i++) {
      expected.add(String.format("bfde%06d", i));
    }
    assertEquals(
        expected,
        resolved,
        "each ProgramStageDataElement must still resolve to its own data element — batching "
            + "changes the query count, never what a proxy resolves to");
    assertTrue(
        queries <= batchedQueryCeiling(DATA_ELEMENTS),
        () ->
            "dereferencing "
                + DATA_ELEMENTS
                + " DataElement proxies issued "
                + queries
                + " queries; batch-size=\""
                + BATCH_SIZE
                + "\" on the DataElement class mapping should bring it to at most "
                + batchedQueryCeiling(DATA_ELEMENTS));
  }

  /**
   * The cost side of the same mechanism, measured rather than assumed. Batching is speculative: it
   * resolves the proxy that was asked for <em>and</em> up to {@code batch-size - 1} others that are
   * merely pending, so a caller that touches one data element out of many hydrates rows it will
   * never read. This asserts only the bound that makes the trade acceptable — one query either way,
   * never more — and prints the rows so the over-fetch is on the record.
   */
  @Test
  @DisplayName("touching a single proxy stays one query, and reports how many rows it drags in")
  void singleDereferenceOverFetchIsBounded() {
    Statistics stats = statistics();

    List<ProgramStageDataElement> psdes =
        em.createQuery(
                "from ProgramStageDataElement psde where psde.programStage.uid = :uid",
                ProgramStageDataElement.class)
            .setParameter("uid", programStage.getUid())
            .getResultList();
    assertEquals(DATA_ELEMENTS, psdes.size(), "fixture must load every programStageDataElement");

    long queriesBefore = stats.getPrepareStatementCount();
    long loadsBefore = stats.getEntityLoadCount();

    // One proxy, out of DATA_ELEMENTS pending ones.
    psdes.get(0).getDataElement().getValueType();

    long queries = stats.getPrepareStatementCount() - queriesBefore;
    long entityLoads = stats.getEntityLoadCount() - loadsBefore;

    System.out.printf(
        "[UGANDA batch-fetch] singleDereference pending=%d queries=%d entitiesHydrated=%d%n",
        DATA_ELEMENTS, queries, entityLoads);

    assertTrue(
        queries <= 2,
        () ->
            "resolving one proxy issued "
                + queries
                + " queries; batching must widen the row set of a fetch, never fan one "
                + "dereference out into a series of them");
  }

  @Test
  @DisplayName("dereferencing every DataElement.categoryCombo must not cost one query each")
  void categoryComboProxiesAreBatchFetched() {
    Statistics stats = statistics();

    List<DataElement> dataElements =
        em.createQuery("from DataElement de where de.uid like 'bfde%'", DataElement.class)
            .getResultList();
    assertEquals(DATA_ELEMENTS, dataElements.size(), "fixture must load every data element");

    long queriesBefore = stats.getPrepareStatementCount();
    long startedAt = System.nanoTime();

    int touched = 0;
    for (DataElement de : dataElements) {
      if (de.getCategoryCombo().getName() != null) {
        touched++;
      }
    }

    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
    long queries = stats.getPrepareStatementCount() - queriesBefore;

    System.out.printf(
        "[UGANDA batch-fetch] categoryCombos=%d dereferenced=%d queries=%d ms=%d%n",
        CATEGORY_COMBOS, touched, queries, elapsedMs);

    assertEquals(DATA_ELEMENTS, touched, "every category combo must have been dereferenced");
    assertTrue(
        queries <= batchedQueryCeiling(CATEGORY_COMBOS),
        () ->
            "dereferencing "
                + CATEGORY_COMBOS
                + " distinct CategoryCombo proxies issued "
                + queries
                + " queries; batch-size=\""
                + BATCH_SIZE
                + "\" on the CategoryCombo class mapping should bring it to at most "
                + batchedQueryCeiling(CATEGORY_COMBOS));
  }
}
