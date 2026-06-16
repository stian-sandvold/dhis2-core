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
package org.hisp.dhis.reservedvalue.hibernate;

import static org.hisp.dhis.common.Objects.TRACKEDENTITYATTRIBUTE;
import static org.hisp.dhis.common.collection.CollectionUtils.isEmpty;

import jakarta.persistence.EntityManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import org.hibernate.query.Query;
import org.hisp.dhis.common.Objects;
import org.hisp.dhis.hibernate.HibernateGenericStore;
import org.hisp.dhis.reservedvalue.ReservedValue;
import org.hisp.dhis.reservedvalue.ReservedValueStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * @author Stian Sandvold
 */
@Repository("org.hisp.dhis.reservedvalue.ReservedValueStore")
public class HibernateReservedValueStore extends HibernateGenericStore<ReservedValue>
    implements ReservedValueStore {

  /**
   * Max rows per JDBC batch flush. Reserved-value generation is capped well below this per request,
   * but chunking keeps memory bounded for any caller that passes a larger list.
   */
  private static final int INSERT_BATCH_SIZE = 1000;

  /**
   * The {@code reservedvalueid} column has no DB default; the id is drawn from {@code
   * reservedvalue_sequence} (the same sequence Hibernate's id generator uses), so the INSERT must
   * supply it explicitly via {@code nextval}.
   */
  private static final String INSERT_SQL =
      "insert into reservedvalue "
          + "(reservedvalueid, ownerobject, owneruid, key, value, expirydate, created) "
          + "values (nextval('reservedvalue_sequence'), ?, ?, ?, ?, ?, ?)";

  public HibernateReservedValueStore(
      EntityManager entityManager, JdbcTemplate jdbcTemplate, ApplicationEventPublisher publisher) {
    super(entityManager, jdbcTemplate, publisher, ReservedValue.class, false);
  }

  @Override
  public List<ReservedValue> getAvailableValues(
      ReservedValue reservedValue, List<String> values, String ownerObject) {
    if (isEmpty(values) || !reservedValue.getOwnerObject().equals(ownerObject)) {
      return List.of();
    }
    List<String> availableValues = getIfAvailable(reservedValue, values);

    return availableValues.stream()
        .map(value -> reservedValue.toBuilder().value(value).build())
        .toList();
  }

  @Override
  public void bulkInsertReservedValues(List<ReservedValue> toAdd) {
    insertReservedValues(toAdd);
  }

  private List<String> getIfAvailable(ReservedValue reservedValue, List<String> values) {

    List<?> teavOrReservedValues =
        getSession()
            .createNamedQuery("getRandomGeneratedValuesNotAvailableNamedQuery")
            .setParameter("teaId", reservedValue.getTrackedEntityAttributeId())
            .setParameter("ownerObject", reservedValue.getOwnerObject())
            .setParameter("ownerUid", reservedValue.getOwnerUid())
            .setParameter("key", reservedValue.getKey())
            .setParameter("values", values.stream().map(String::toLowerCase).toList())
            .list();

    return values.stream().filter(rv -> !teavOrReservedValues.contains(rv)).toList();
  }

  @Override
  public void reserveValues(List<ReservedValue> reservedValues) {
    insertReservedValues(reservedValues);
  }

  /**
   * Bulk-inserts reserved values via the Spring transaction-bound connection (chunked {@code
   * batchUpdate}). Replaces the legacy {@code org.hisp.quick.BatchHandler}, which wrote on its own
   * autoCommit connection and could not roll back with the caller.
   *
   * <p>Behavioural change vs. the old BatchHandler: a failure now propagates as a {@link
   * org.springframework.dao.DataAccessException} and rolls back atomically with the caller's
   * transaction, instead of being logged and swallowed. On the now-shared transactional connection
   * a swallowed failure could not be recovered from anyway (PostgreSQL aborts the surrounding
   * transaction), so propagation turns a silent partial/failed write into a loud, atomic one.
   */
  private void insertReservedValues(List<ReservedValue> reservedValues) {
    if (isEmpty(reservedValues)) {
      return;
    }
    for (int from = 0; from < reservedValues.size(); from += INSERT_BATCH_SIZE) {
      List<ReservedValue> batch =
          reservedValues.subList(from, Math.min(from + INSERT_BATCH_SIZE, reservedValues.size()));
      jdbcTemplate.batchUpdate(
          INSERT_SQL,
          new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
              ReservedValue rv = batch.get(i);
              ps.setString(1, rv.getOwnerObject());
              ps.setString(2, rv.getOwnerUid());
              ps.setString(3, rv.getKey());
              ps.setString(4, rv.getValue());
              ps.setTimestamp(5, toTimestamp(rv.getExpiryDate()));
              ps.setTimestamp(6, toTimestamp(rv.getCreated()));
            }

            @Override
            public int getBatchSize() {
              return batch.size();
            }
          });
    }
  }

  private static Timestamp toTimestamp(Date date) {
    return date == null ? null : new Timestamp(date.getTime());
  }

  @Override
  public int getNumberOfUsedValues(ReservedValue reservedValue) {
    Query<Long> query =
        getTypedQuery("SELECT count(*) FROM ReservedValue WHERE owneruid = :uid AND key = :key");

    Long count =
        query
            .setParameter("uid", reservedValue.getOwnerUid())
            .setParameter("key", reservedValue.getKey())
            .getSingleResult();

    if (Objects.valueOf(reservedValue.getOwnerObject()).equals(TRACKEDENTITYATTRIBUTE)) {
      Query<Long> attrQuery =
          getTypedQuery(
              "SELECT count(*) "
                  + "FROM TrackedEntityAttributeValue "
                  + "WHERE attribute = "
                  + "( FROM TrackedEntityAttribute "
                  + "WHERE uid = :uid ) "
                  + "AND value LIKE :value ");

      count +=
          attrQuery
              .setParameter("uid", reservedValue.getOwnerUid())
              .setParameter("value", reservedValue.getValue())
              .getSingleResult();
    }

    return count.intValue();
  }

  @Override
  public boolean useReservedValue(String ownerUID, String value) {
    return getQuery("DELETE FROM ReservedValue WHERE owneruid = :uid AND value = :value")
            .setParameter("uid", ownerUID)
            .setParameter("value", value)
            .executeUpdate()
        == 1;
  }

  @Override
  public void deleteReservedValueByUid(String uid) {
    getQuery("DELETE FROM ReservedValue WHERE owneruid = :uid")
        .setParameter("uid", uid)
        .executeUpdate();
  }

  @Override
  public boolean isReserved(String ownerObject, String ownerUID, String value) {
    String hql =
        "from ReservedValue rv where rv.ownerObject =:ownerObject and rv.ownerUid =:ownerUid "
            + "and rv.value =:value";

    return !getQuery(hql)
        .setParameter("ownerObject", ownerObject)
        .setParameter("ownerUid", ownerUID)
        .setParameter("value", value)
        .getResultList()
        .isEmpty();
  }

  @Override
  public int removeExpiredValues() {
    return jdbcTemplate.update(
        "DELETE FROM reservedvalue WHERE reservedvalueid IN "
            + "(SELECT reservedvalueid FROM reservedvalue WHERE expirydate < now() LIMIT ?)",
        DELETE_BATCH_SIZE);
  }

  @Override
  public int removeUsedValues() {
    return jdbcTemplate.update(
        "DELETE FROM reservedvalue WHERE reservedvalueid IN ("
            + "SELECT rv.reservedvalueid FROM reservedvalue rv "
            + "JOIN trackedentityattribute tea ON rv.owneruid = tea.uid "
            + "JOIN trackedentityattributevalue teav ON teav.trackedentityattributeid = tea.trackedentityattributeid "
            + "AND lower(teav.value) = lower(rv.value) "
            + "LIMIT ?)",
        DELETE_BATCH_SIZE);
  }
}
