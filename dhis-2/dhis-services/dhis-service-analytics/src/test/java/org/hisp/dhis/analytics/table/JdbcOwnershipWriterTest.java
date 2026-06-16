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
package org.hisp.dhis.analytics.table;

import static java.util.Calendar.DECEMBER;
import static java.util.Calendar.FEBRUARY;
import static java.util.Calendar.JANUARY;
import static org.apache.commons.lang3.reflect.FieldUtils.writeField;
import static org.hisp.dhis.analytics.table.writer.JdbcOwnershipWriter.ENDDATE;
import static org.hisp.dhis.analytics.table.writer.JdbcOwnershipWriter.OU;
import static org.hisp.dhis.analytics.table.writer.JdbcOwnershipWriter.STARTDATE;
import static org.hisp.dhis.analytics.table.writer.JdbcOwnershipWriter.TRACKEDENTITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hisp.dhis.analytics.table.writer.JdbcOwnershipBatchWriter;
import org.hisp.dhis.analytics.table.writer.JdbcOwnershipWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@see JdbcOwnershipWriter} Tester. Verifies the ownership-period combining logic by asserting the
 * row maps handed to the (mocked) {@link JdbcOwnershipBatchWriter} — decoupled from the SQL the
 * writer's sink produces (previously this test asserted the quick BatchHandler's literal SQL).
 *
 * @author Jim Grace
 */
@ExtendWith(MockitoExtension.class)
class JdbcOwnershipWriterTest {
  @Mock private JdbcOwnershipBatchWriter batchWriter;

  private JdbcOwnershipWriter writer;

  private static final String teA = "teAaaaaaaa";

  private static final String teB = "teBbbbbbbb";

  private static final String ouA = "ouAaaaaaaaa";

  private static final String ouB = "ouBbbbbbbbb";

  private static final Date date_2022_01_01 = new GregorianCalendar(2022, JANUARY, 1).getTime();

  private static final Date date_2022_01_02 = new GregorianCalendar(2022, JANUARY, 2).getTime();

  private static final Date date_2022_02_01 = new GregorianCalendar(2022, FEBRUARY, 1).getTime();

  private static final Date date_2022_02_02 = new GregorianCalendar(2022, FEBRUARY, 2).getTime();

  private static final Date FAR_PAST = new GregorianCalendar(1000, JANUARY, 1).getTime();

  private static final Date FAR_FUTURE = new GregorianCalendar(9999, DECEMBER, 31).getTime();

  @BeforeEach
  public void setUp() {
    writer = JdbcOwnershipWriter.getInstance(batchWriter);
  }

  @Test
  void testWriteNoOwnershipChanges() {
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouA, ENDDATE, date_2022_01_01));
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouA, ENDDATE, date_2022_02_01));
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouA, ENDDATE, null));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouB, ENDDATE, date_2022_01_01));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouB, ENDDATE, date_2022_02_01));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouB, ENDDATE, null));

    // Ownership never changed for either tracked entity -> nothing written.
    verify(batchWriter, never()).addObject(any());
  }

  @Test
  void testWriteOneOwnershipChange() {
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouA, ENDDATE, date_2022_01_01));
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouA, ENDDATE, date_2022_02_01));
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouB, ENDDATE, null));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouA, ENDDATE, date_2022_01_01));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouA, ENDDATE, date_2022_02_01));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouA, ENDDATE, null));

    assertRowsWritten(
        row(teA, ouA, FAR_PAST, date_2022_02_01), row(teA, ouB, date_2022_02_02, FAR_FUTURE));
  }

  @Test
  void testWriteTwoOwnershipChanges() {
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouA, ENDDATE, date_2022_01_01));
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouB, ENDDATE, date_2022_02_01));
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouA, ENDDATE, null));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouA, ENDDATE, date_2022_01_01));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouA, ENDDATE, date_2022_02_01));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouA, ENDDATE, null));

    assertRowsWritten(
        row(teA, ouA, FAR_PAST, date_2022_01_01),
        row(teA, ouB, date_2022_01_02, date_2022_02_01),
        row(teA, ouA, date_2022_02_02, FAR_FUTURE));
  }

  @Test
  void testWriteThreeOwnershipChanges() {
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouA, ENDDATE, date_2022_01_01));
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouB, ENDDATE, date_2022_02_01));
    writer.write(mapOf(TRACKEDENTITY, teA, OU, ouA, ENDDATE, null));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouA, ENDDATE, date_2022_01_01));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouA, ENDDATE, date_2022_02_01));
    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouB, ENDDATE, null));

    assertRowsWritten(
        row(teA, ouA, FAR_PAST, date_2022_01_01),
        row(teA, ouB, date_2022_01_02, date_2022_02_01),
        row(teA, ouA, date_2022_02_02, FAR_FUTURE),
        row(teB, ouA, FAR_PAST, date_2022_02_01),
        row(teB, ouB, date_2022_02_02, FAR_FUTURE));
  }

  @Test
  void testWriteWhenEndDateIsNull() throws IllegalAccessException {
    JdbcOwnershipWriter writer = JdbcOwnershipWriter.getInstance(batchWriter);
    Map<String, Object> prevRow = new HashMap<>();
    writeField(writer, "prevRow", prevRow, true);

    writer.write(mapOf(TRACKEDENTITY, teB, OU, ouB, ENDDATE, null));

    assertNotNull(prevRow.get(ENDDATE));
  }

  // -------------------------------------------------------------------------
  // Supportive methods
  // -------------------------------------------------------------------------

  /** Captures every row handed to the batch writer and asserts they equal the expected rows. */
  @SafeVarargs
  private void assertRowsWritten(Map<String, Object>... expected) {
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(batchWriter, org.mockito.Mockito.times(expected.length)).addObject(captor.capture());
    assertEquals(List.of(expected), captor.getAllValues());
  }

  private Map<String, Object> row(String teuid, String ou, Date startDate, Date endDate) {
    return Map.of(TRACKEDENTITY, teuid, OU, ou, STARTDATE, startDate, ENDDATE, endDate);
  }

  /**
   * Creates a map of three key/value pairs that allows nulls (because the database can return nulls
   * and the logic relies on that).
   */
  private <K, V> Map<K, V> mapOf(K key1, V value1, K key2, V value2, K key3, V value3) {
    HashMap<K, V> map = new HashMap<>();
    map.put(key1, value1);
    map.put(key2, value2);
    map.put(key3, value3);
    return map;
  }
}
