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
package org.hisp.dhis.dxf2.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.Serializable;
import java.util.Date;
import java.util.Map;
import org.hisp.dhis.dataset.CompleteDataSetRegistrationService;
import org.hisp.dhis.dxf2.dataset.CompleteDataSetRegistrationExchangeService;
import org.hisp.dhis.scheduling.JobProgress;
import org.hisp.dhis.setting.Settings;
import org.hisp.dhis.setting.SystemSettings;
import org.hisp.dhis.setting.SystemSettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestTemplate;

/**
 * Unit test for {@link CompleteDataSetRegistrationSynchronization}.
 *
 * <p>Regression test for <a href="https://dhis2.atlassian.net/browse/DHIS2-21605">DHIS2-21605</a>:
 * after a successful completeness sync the {@code keyLastCompleteDataSetRegistrationSyncSuccess}
 * system setting must be persisted in a form the settings layer can read back. Storing {@link
 * Date#toString()} (the pre-fix behaviour) produced a value that {@code SystemSettings.asDate}
 * cannot parse (it accepts epoch-millis or ISO local date-time only), so the watermark was silently
 * lost — it always read back as epoch 0 and a warning was logged on the next run.
 */
@ExtendWith(MockitoExtension.class)
class CompleteDataSetRegistrationSynchronizationTest {

  private static final String KEY = "keyLastCompleteDataSetRegistrationSyncSuccess";

  @Mock private SystemSettingsService settingsService;
  @Mock private RestTemplate restTemplate;
  @Mock private CompleteDataSetRegistrationService completeDataSetRegistrationService;
  @Mock private CompleteDataSetRegistrationExchangeService exchangeService;

  @Captor private ArgumentCaptor<Serializable> valueCaptor;

  /**
   * Drives the "nothing to synchronize" branch (the simplest path that still persists the success
   * timestamp), captures the value handed to {@link SystemSettingsService#put}, and feeds it back
   * through the real settings (de)serialization to prove it round-trips to the start time rather
   * than collapsing to epoch 0.
   */
  @Test
  void persistedLastSyncSuccessTimestampRoundTrips() {
    when(settingsService.getCurrentSettings()).thenReturn(SystemSettings.of(Map.of()));
    when(completeDataSetRegistrationService.getCompleteDataSetCountLastUpdatedAfter(
            any(Date.class)))
        .thenReturn(0);

    CompleteDataSetRegistrationSynchronization sync =
        new CompleteDataSetRegistrationSynchronization(
            settingsService, restTemplate, completeDataSetRegistrationService, exchangeService);

    Date before = new Date(System.currentTimeMillis() - 1000);

    try (MockedStatic<SyncUtils> syncUtils = mockStatic(SyncUtils.class)) {
      syncUtils
          .when(() -> SyncUtils.testServerAvailability(any(), eq(restTemplate)))
          .thenReturn(new AvailabilityStatus(true, "available", HttpStatus.OK));

      SynchronizationResult result = sync.synchronizeData(JobProgress.noop());

      assertEquals(SynchronizationStatus.SUCCESS, result.status);
    }

    Date after = new Date(System.currentTimeMillis() + 1000);

    verify(settingsService).put(eq(KEY), valueCaptor.capture());
    Serializable persisted = valueCaptor.getValue();

    // Reconstruct the stored raw string exactly as the persistence layer would, then read it back
    // through the real SystemSettings parser.
    String rawValue = Settings.valueOf(persisted);
    Date readBack =
        SystemSettings.of(Map.of(KEY, rawValue)).getLastCompleteDataSetRegistrationSyncSuccess();

    assertNotEquals(
        new Date(0L),
        readBack,
        "Persisted sync-success timestamp must not collapse to epoch 0 (DHIS2-21605)");
    assertTrue(
        !readBack.before(before) && !readBack.after(after),
        "Persisted sync-success timestamp must round-trip to the sync start time, but was "
            + readBack);
  }
}
