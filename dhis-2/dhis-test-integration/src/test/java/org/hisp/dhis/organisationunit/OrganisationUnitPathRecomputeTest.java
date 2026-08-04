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
package org.hisp.dhis.organisationunit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.hisp.dhis.common.IdentifiableObjectManager;
import org.hisp.dhis.test.integration.PostgresIntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code OrganisationUnit.path} and {@code hierarchyLevel} are mapped with property access, so
 * Hibernate reads them <em>through their getters</em> on every dirty check, cascade and flush. Both
 * getters recomputed the value by walking the ancestor chain, and each ancestor is normally an
 * uninitialised proxy whose {@code getUid()} issues a {@code SELECT}.
 *
 * <p>That is the mechanism behind the stack captured in the Uganda Glowroot traces (UGANDA-8):
 *
 * <pre>
 * Cascade.cascade:184 -> AbstractEntityPersister.getPropertyValue:5294 -> GetterMethodImpl.get
 *   -> OrganisationUnit.getPath -> OrganisationUnit$HibernateProxy.getUid()
 *     -> AbstractLazyInitializer.initialize:185 -> SessionImpl.immediateLoad:1043
 *       -> DefaultLoadEventListener.loadFromDatasource:571 -> PgPreparedStatement.executeQuery
 * </pre>
 *
 * <p>A dirty check of an organisation unit was therefore never a field comparison — it was two
 * hierarchy walks plus one query per uninitialised ancestor.
 *
 * <p>The quantity that matters is the <em>shape</em>: reading the path of an unmodified, freshly
 * loaded organisation unit must cost no queries at all. The correctness tests alongside pin the
 * behaviour the recompute was providing, because it was load-bearing in three places —
 * re-parenting, {@code updatePaths()} and {@code forceUpdatePaths()}.
 */
@Transactional
class OrganisationUnitPathRecomputeTest extends PostgresIntegrationTestBase {

  /**
   * Uganda-like shape: root / district / subcounty / facility. Committed at 300 leaves so the suite
   * stays quick; raise with {@code -Dpath.districts} / {@code -Dpath.subcounties} / {@code
   * -Dpath.facilities} to measure at the traced scale (~2250 leaves).
   */
  private static final int DISTRICTS = Integer.getInteger("path.districts", 5);

  private static final int SUBCOUNTIES_PER_DISTRICT = Integer.getInteger("path.subcounties", 6);

  private static final int FACILITIES_PER_SUBCOUNTY = Integer.getInteger("path.facilities", 10);

  /** 5 * 6 * 10 = 300 leaves over 36 ancestors. The trace had ~2250 leaves. */
  private static final int LEAVES = DISTRICTS * SUBCOUNTIES_PER_DISTRICT * FACILITIES_PER_SUBCOUNTY;

  @Autowired private OrganisationUnitService organisationUnitService;
  @Autowired private IdentifiableObjectManager idObjectManager;
  @Autowired private EntityManager em;
  @Autowired private JdbcTemplate jdbcTemplate;

  private OrganisationUnit root;
  private final List<OrganisationUnit> leaves = new ArrayList<>();

  @BeforeEach
  void setUp() {
    root = createOrganisationUnit('R');
    organisationUnitService.addOrganisationUnit(root);

    for (int d = 0; d < DISTRICTS; d++) {
      OrganisationUnit district = createOrganisationUnit("D" + d, root);
      organisationUnitService.addOrganisationUnit(district);

      for (int s = 0; s < SUBCOUNTIES_PER_DISTRICT; s++) {
        OrganisationUnit subcounty = createOrganisationUnit("S" + d + "_" + s, district);
        organisationUnitService.addOrganisationUnit(subcounty);

        for (int f = 0; f < FACILITIES_PER_SUBCOUNTY; f++) {
          OrganisationUnit facility =
              createOrganisationUnit("F" + d + "_" + s + "_" + f, subcounty);
          organisationUnitService.addOrganisationUnit(facility);
          leaves.add(facility);
        }
      }
    }

    idObjectManager.flush();
    idObjectManager.clear();
  }

  private Statistics statistics() {
    return em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
  }

  /**
   * Loads every leaf with one query. Their ancestors are left as uninitialised proxies, which is
   * the state the traced request was in.
   */
  private List<OrganisationUnit> loadLeavesWithProxiedAncestors() {
    return em.createQuery(
            "from OrganisationUnit ou where ou.name like 'F%' order by ou.name",
            OrganisationUnit.class)
        .getResultList();
  }

  @Test
  @DisplayName("flushing unmodified organisation units must not query for their ancestors")
  void flushOfUnmodifiedUnitsIssuesNoAncestorQueries() {
    Statistics stats = statistics();
    stats.setStatisticsEnabled(true);

    List<OrganisationUnit> loaded = loadLeavesWithProxiedAncestors();
    assertEquals(LEAVES, loaded.size(), "fixture must load every leaf");

    long queriesBefore = stats.getPrepareStatementCount();
    long loadsBefore = stats.getEntityLoadCount();

    long startedAt = System.nanoTime();
    em.flush();
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

    long queries = stats.getPrepareStatementCount() - queriesBefore;
    long entityLoads = stats.getEntityLoadCount() - loadsBefore;

    System.out.printf(
        "[UGANDA path] leaves=%d flushQueries=%d ancestorEntityLoads=%d flushMs=%d%n",
        LEAVES, queries, entityLoads, elapsedMs);

    // Nothing was modified, so the flush has nothing to write and nothing to read. Before the fix
    // every leaf's getPath()/getHierarchyLevel() walked into a proxy and pulled its ancestors in.
    assertEquals(
        0,
        entityLoads,
        () ->
            "flushing "
                + LEAVES
                + " unmodified organisation units must load no ancestors, but loaded "
                + entityLoads
                + " (the path/hierarchyLevel getters are walking the ancestor chain)");
    assertEquals(0, queries, "an empty flush must issue no SQL");
  }

  /**
   * The production shape. Under the default {@code FlushModeType.AUTO} every query dirty-checks the
   * whole persistence context first, and each dirty check reads {@code path} and {@code
   * hierarchyLevel} through their getters — so the ancestor walk runs once per managed unit per
   * query. This is the cost that made the {@code dataValueSets} import quadratic, and the part that
   * survives the auto-flush fix, because a commit flush still reads every property once.
   *
   * <p>Reported, not asserted: wall-clock is machine-dependent. The deterministic assertion is in
   * {@link #flushOfUnmodifiedUnitsIssuesNoAncestorQueries}.
   */
  @Test
  @DisplayName("benchmark: per-query auto-flush cost over a loaded organisation unit graph")
  void benchmarkPerQueryAutoFlushCost() {
    int queries = Integer.getInteger("path.bench.queries", 200);

    Statistics stats = statistics();
    stats.setStatisticsEnabled(true);

    List<OrganisationUnit> loaded = loadLeavesWithProxiedAncestors();
    assertEquals(LEAVES, loaded.size());

    long queriesBefore = stats.getPrepareStatementCount();
    long loadsBefore = stats.getEntityLoadCount();

    long startedAt = System.nanoTime();
    for (int i = 0; i < queries; i++) {
      // Any query triggers autoFlushIfRequired, which dirty-checks every managed entity before it
      // decides whether a flush is actually needed.
      em.createQuery("select count(*) from DataElement").getSingleResult();
    }
    long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

    System.out.printf(
        "[UGANDA path bench] leaves=%d queries=%d totalMs=%d msPerQuery=%.3f "
            + "sql=%d ancestorEntityLoads=%d%n",
        LEAVES,
        queries,
        elapsedMs,
        elapsedMs / (double) queries,
        stats.getPrepareStatementCount() - queriesBefore,
        stats.getEntityLoadCount() - loadsBefore);

    assertTrue(elapsedMs >= 0, "benchmark must complete");
  }

  @Test
  @DisplayName("reading path on freshly loaded units must not query, and must match the column")
  void readingPathOfLoadedUnitIsFreeAndCorrect() {
    Statistics stats = statistics();
    stats.setStatisticsEnabled(true);

    List<OrganisationUnit> loaded = loadLeavesWithProxiedAncestors();

    long loadsBefore = stats.getEntityLoadCount();

    for (OrganisationUnit leaf : loaded) {
      assertNotNull(leaf.getPath());
      assertEquals(4, leaf.getHierarchyLevel(), "root/district/subcounty/facility is level 4");
      assertEquals(4, leaf.getLevel(), "getLevel() must agree with getHierarchyLevel()");
    }

    assertEquals(
        0,
        stats.getEntityLoadCount() - loadsBefore,
        "reading path/hierarchyLevel on loaded units must not initialise ancestor proxies");

    // The memoised value must be the persisted value, not merely non-null.
    for (OrganisationUnit leaf : loaded) {
      String stored =
          jdbcTemplate.queryForObject(
              "select path from organisationunit where uid = ?", String.class, leaf.getUid());
      assertEquals(stored, leaf.getPath(), "getPath() must equal the persisted column");
      assertEquals(stored, leaf.getStoredPath(), "getStoredPath() must equal the persisted column");
    }
  }

  @Test
  @DisplayName("re-parenting a unit must recompute and persist its new path and level")
  void reParentingPersistsNewPath() {
    OrganisationUnit newParent = organisationUnitService.getOrganisationUnitByName("D0").get(0);
    OrganisationUnit moved = organisationUnitService.getOrganisationUnitByName("F4_5_9").get(0);

    String pathBefore = moved.getPath();
    assertEquals(4, moved.getHierarchyLevel());

    moved.setParent(newParent);
    organisationUnitService.updateOrganisationUnit(moved);
    idObjectManager.flush();

    String expected = newParent.getPath() + "/" + moved.getUid();
    assertEquals(expected, moved.getPath(), "in-memory path must reflect the new parent");
    assertEquals(3, moved.getHierarchyLevel(), "moved one level up");

    String stored =
        jdbcTemplate.queryForObject(
            "select path from organisationunit where uid = ?", String.class, moved.getUid());
    assertEquals(expected, stored, "the new path must be written to the column");
    assertTrue(!expected.equals(pathBefore), "fixture must actually move the unit");

    Integer storedLevel =
        jdbcTemplate.queryForObject(
            "select hierarchylevel from organisationunit where uid = ?",
            Integer.class,
            moved.getUid());
    assertEquals(3, storedLevel, "the new hierarchyLevel must be written to the column");
  }

  @Test
  @DisplayName("updatePaths() must repair a NULL path by loading alone")
  void updatePathsRepairsNullPath() {
    OrganisationUnit target = organisationUnitService.getOrganisationUnitByName("F0_0_0").get(0);
    String expected = target.getPath();

    idObjectManager.clear();
    // Only the path is nulled. Nulling hierarchylevel as well makes the row unloadable on both the
    // base and the fix: OrganisationUnit.level is mapped read-only onto the same column with a
    // primitive int setter, so hydration throws PropertyAccessException. That is a separate,
    // pre-existing defect and not what this test is about.
    jdbcTemplate.update("update organisationunit set path = null where uid = ?", target.getUid());

    organisationUnitService.updatePaths();
    idObjectManager.flush();

    String stored =
        jdbcTemplate.queryForObject(
            "select path from organisationunit where uid = ?", String.class, target.getUid());
    assertEquals(expected, stored, "updatePaths() must rewrite a NULL path");
  }

  @Test
  @DisplayName("forceUpdatePaths() must repair a path that is present but wrong")
  void forceUpdatePathsRepairsStalePath() {
    OrganisationUnit target = organisationUnitService.getOrganisationUnitByName("F0_0_1").get(0);
    String expected = target.getPath();

    idObjectManager.clear();
    jdbcTemplate.update(
        "update organisationunit set path = ?, hierarchylevel = ? where uid = ?",
        "/deliberately/wrong/" + target.getUid(),
        99,
        target.getUid());

    organisationUnitService.forceUpdatePaths();
    idObjectManager.flush();

    String stored =
        jdbcTemplate.queryForObject(
            "select path from organisationunit where uid = ?", String.class, target.getUid());
    assertEquals(expected, stored, "forceUpdatePaths() must rewrite a stale path");

    Integer storedLevel =
        jdbcTemplate.queryForObject(
            "select hierarchylevel from organisationunit where uid = ?",
            Integer.class,
            target.getUid());
    assertEquals(4, storedLevel, "forceUpdatePaths() must rewrite a stale hierarchyLevel");
  }

  @Test
  @DisplayName("a unit built in memory with no parent set yet still reports a self path")
  void inMemoryUnitWithoutParentHasSelfPath() {
    OrganisationUnit orphan = createOrganisationUnit('Z');
    assertEquals("/" + orphan.getUid(), orphan.getPath());
    assertEquals(1, orphan.getHierarchyLevel());

    OrganisationUnit child = new OrganisationUnit();
    child.setAutoFields();
    child.setName("child");
    child.setShortName("child");
    child.setParent(orphan);
    assertEquals(
        "/" + orphan.getUid() + "/" + child.getUid(),
        child.getPath(),
        "setting a parent after construction must invalidate the memo");
    assertEquals(2, child.getHierarchyLevel());
  }
}
