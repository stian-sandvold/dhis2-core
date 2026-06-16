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
package org.hisp.dhis.analytics.table.writer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Buffered, chunked {@link JdbcTemplate} writer for the {@code analytics_ownership} staging tables.
 *
 * <p>Replaces the legacy {@code org.hisp.quick.MappingBatchHandler}: it builds a parameterised
 * INSERT for the given (dynamic) table name and column list, buffers row maps, and flushes via
 * {@link JdbcTemplate#batchUpdate} every {@link #BATCH_SIZE} rows (and on {@link #close()}).
 *
 * <p>It runs on the analytics {@code JdbcTemplate} connection in autocommit — analytics table
 * generation runs outside any request transaction and manages its own commit cadence, which matches
 * the old handler's separate-connection model (so this migration does <b>not</b> change the
 * transaction semantics, unlike the request-path BatchHandler migrations).
 *
 * @author (BatchHandler -> JdbcTemplate migration)
 */
public class JdbcOwnershipBatchWriter implements AutoCloseable {
  static final int BATCH_SIZE = 1000;

  private final JdbcTemplate jdbcTemplate;

  private final List<String> columns;

  private final String insertSql;

  private final List<Map<String, Object>> buffer = new ArrayList<>();

  public JdbcOwnershipBatchWriter(
      JdbcTemplate jdbcTemplate, String tableName, List<String> columns) {
    this.jdbcTemplate = jdbcTemplate;
    this.columns = List.copyOf(columns);
    this.insertSql =
        "insert into "
            + tableName
            + " ("
            + String.join(",", columns)
            + ") values ("
            + String.join(",", Collections.nCopies(columns.size(), "?"))
            + ")";
  }

  /** Buffers a row and flushes the batch once {@link #BATCH_SIZE} rows have accumulated. */
  public void addObject(Map<String, Object> row) {
    buffer.add(row);
    if (buffer.size() >= BATCH_SIZE) {
      flush();
    }
  }

  /** Flushes the buffered rows as one JDBC batch (no-op if empty). */
  public void flush() {
    if (buffer.isEmpty()) {
      return;
    }
    List<Map<String, Object>> batch = new ArrayList<>(buffer);
    buffer.clear();
    jdbcTemplate.batchUpdate(
        insertSql,
        new BatchPreparedStatementSetter() {
          @Override
          public void setValues(PreparedStatement ps, int i) throws SQLException {
            Map<String, Object> row = batch.get(i);
            for (int c = 0; c < columns.size(); c++) {
              Object value = row.get(columns.get(c));
              if (value instanceof Date date) {
                // date/timestamp columns; the driver coerces a timestamp into a date column
                ps.setTimestamp(c + 1, new Timestamp(date.getTime()));
              } else {
                ps.setObject(c + 1, value);
              }
            }
          }

          @Override
          public int getBatchSize() {
            return batch.size();
          }
        });
  }

  @Override
  public void close() {
    flush();
  }
}
