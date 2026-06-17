/*
 * Copyright (c) 2004-2026, University of Oslo
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
package org.hisp.dhis.jdbc.bulk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;

/**
 * Standard bulk-insert helper for PostgreSQL, using the {@code COPY ... FROM STDIN WITH (FORMAT
 * binary)} protocol on the <b>caller's transaction-bound connection</b>.
 *
 * <p>This is the replacement for the legacy {@code org.hisp.quick} {@code BatchHandler}. Unlike that
 * library — which opened its own raw {@link java.sql.Connection} in autoCommit mode (so its writes
 * neither saw nor rolled back with the caller's transaction) — this helper obtains the connection
 * via {@link DataSourceUtils}, so the COPY participates in and rolls back with the surrounding Spring
 * {@code @Transactional} boundary.
 *
 * <p>Binary COPY was chosen over {@code JdbcTemplate.batchUpdate} and {@code unnest()} after an
 * isolated benchmark sweep across the three payload shapes in use (all-integer, string-heavy, and
 * mixed): it beat {@code batchUpdate} at every size and shape — by ~3-4x on integers and ~5-7x on
 * string/mixed payloads at scale — and avoids the text-escaping concerns of text COPY.
 *
 * <p>Rows are flushed in chunks of {@link #CHUNK_ROWS} to keep heap usage bounded for large
 * generations (e.g. analytics ownership). Each chunk is a self-contained binary COPY stream.
 *
 * <p>Usage: declare the target columns (name + {@link ColumnType}, in insert order), then supply
 * each row as an {@code Object[]} of the same length/order. A {@code null} element is written as SQL
 * {@code NULL}. Columns with a database default (e.g. a surrogate-id sequence) should simply be
 * omitted from the column list — PostgreSQL fills them.
 */
@Component
@RequiredArgsConstructor
public class PostgresBulkInsert {
  private final DataSource dataSource;

  /** Rows per COPY flush; bounds heap for large inputs. */
  private static final int CHUNK_ROWS = 50_000;

  /** PostgreSQL binary COPY signature + (empty) flags + (empty) header extension. */
  private static final byte[] HEADER = {
    'P', 'G', 'C', 'O', 'P', 'Y', '\n', (byte) 0xFF, '\r', '\n', 0, 0, 0, 0, 0, 0, 0, 0, 0
  };

  /** Epoch milliseconds at 2000-01-01 00:00:00 UTC — the PostgreSQL binary timestamp/date epoch. */
  private static final long PG_EPOCH_MILLIS = 946_684_800_000L;

  private static final long MILLIS_PER_DAY = 86_400_000L;

  /** Supported column types (the set used across all current call sites). */
  public enum ColumnType {
    INT4,
    INT8,
    BOOLEAN,
    TEXT,
    TIMESTAMP,
    DATE
  }

  /** A target column: its name and PostgreSQL type. */
  public record Column(String name, ColumnType type) {}

  /**
   * Bulk-inserts {@code rows} into {@code table} via binary COPY on the current transaction's
   * connection. Each row must have one value per column, in the same order as {@code columns}.
   *
   * @return the number of rows written.
   */
  public long copyInto(String table, List<Column> columns, Iterable<Object[]> rows) {
    String sql = buildCopySql(table, columns);
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      CopyManager copyManager = connection.unwrap(PGConnection.class).getCopyAPI();
      ColumnType[] types = columns.stream().map(Column::type).toArray(ColumnType[]::new);

      long total = 0;
      ByteArrayOutputStream buffer = new ByteArrayOutputStream(1 << 20);
      DataOutputStream out = new DataOutputStream(buffer);
      out.write(HEADER);
      int inChunk = 0;

      for (Object[] row : rows) {
        writeRow(out, types, row);
        total++;
        if (++inChunk == CHUNK_ROWS) {
          flush(copyManager, sql, out, buffer);
          buffer.reset();
          out.write(HEADER);
          inChunk = 0;
        }
      }
      if (inChunk > 0) {
        flush(copyManager, sql, out, buffer);
      }
      return total;
    } catch (SQLException | IOException ex) {
      throw new IllegalStateException("Bulk COPY into '" + table + "' failed", ex);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  private static void flush(
      CopyManager copyManager, String sql, DataOutputStream out, ByteArrayOutputStream buffer)
      throws IOException, SQLException {
    out.writeShort(-1); // binary trailer
    out.flush();
    copyManager.copyIn(sql, new ByteArrayInputStream(buffer.toByteArray(), 0, buffer.size()));
  }

  private static void writeRow(DataOutputStream out, ColumnType[] types, Object[] row)
      throws IOException {
    if (row.length != types.length) {
      throw new IllegalArgumentException(
          "Row has " + row.length + " values but " + types.length + " columns were declared");
    }
    out.writeShort(types.length);
    for (int i = 0; i < types.length; i++) {
      writeField(out, types[i], row[i]);
    }
  }

  private static void writeField(DataOutputStream out, ColumnType type, Object value)
      throws IOException {
    if (value == null) {
      out.writeInt(-1);
      return;
    }
    switch (type) {
      case INT4 -> {
        out.writeInt(4);
        out.writeInt(((Number) value).intValue());
      }
      case INT8 -> {
        out.writeInt(8);
        out.writeLong(((Number) value).longValue());
      }
      case BOOLEAN -> {
        out.writeInt(1);
        out.writeByte(((Boolean) value) ? 1 : 0);
      }
      case TEXT -> {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
      }
      case TIMESTAMP -> {
        out.writeInt(8);
        out.writeLong((((Date) value).getTime() - PG_EPOCH_MILLIS) * 1000L); // micros since 2000
      }
      case DATE -> {
        out.writeInt(4);
        out.writeInt(
            (int) Math.floorDiv(((Date) value).getTime() - PG_EPOCH_MILLIS, MILLIS_PER_DAY));
      }
    }
  }

  private static String buildCopySql(String table, List<Column> columns) {
    StringBuilder sql = new StringBuilder("COPY ").append(table).append(" (");
    for (int i = 0; i < columns.size(); i++) {
      if (i > 0) {
        sql.append(',');
      }
      sql.append(columns.get(i).name());
    }
    return sql.append(") FROM STDIN WITH (FORMAT binary)").toString();
  }
}
