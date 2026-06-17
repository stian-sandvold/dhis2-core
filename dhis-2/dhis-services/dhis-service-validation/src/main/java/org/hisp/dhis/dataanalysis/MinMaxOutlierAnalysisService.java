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
package org.hisp.dhis.dataanalysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hisp.dhis.category.CategoryOptionCombo;
import org.hisp.dhis.common.ValueType;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.datavalue.DeflatedDataValue;
import org.hisp.dhis.jdbc.bulk.PostgresBulkInsert;
import org.hisp.dhis.jdbc.bulk.PostgresBulkInsert.Column;
import org.hisp.dhis.jdbc.bulk.PostgresBulkInsert.ColumnType;
import org.hisp.dhis.minmax.MinMaxDataElementService;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.period.Period;
import org.hisp.dhis.system.util.MathUtils;
import org.joda.time.DateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Lars Helge Overland
 */
@Slf4j
@RequiredArgsConstructor
@Service("org.hisp.dhis.dataanalysis.MinMaxOutlierAnalysisService")
public class MinMaxOutlierAnalysisService implements MinMaxDataAnalysisService {
  private static final EnumSet<ValueType> POS_INT_TYPES =
      EnumSet.of(ValueType.INTEGER_POSITIVE, ValueType.INTEGER_ZERO_OR_POSITIVE);

  private final DataAnalysisStore dataAnalysisStore;

  private final MinMaxDataElementService minMaxDataElementService;

  private final PostgresBulkInsert bulkInsert;

  /**
   * Target columns for the bulk insert. Column order matches the legacy {@code
   * MinMaxDataElementBatchHandler}. {@code minmaxdataelementid} is omitted: it has a DB default
   * ({@code nextval('hibernate_sequence')}, see migration V2_43_5), so the database assigns it —
   * same as the modern {@code HibernateMinMaxDataElementStore.upsertValues}.
   */
  private static final List<Column> COLUMNS =
      List.of(
          new Column("sourceid", ColumnType.INT8),
          new Column("dataelementid", ColumnType.INT8),
          new Column("categoryoptioncomboid", ColumnType.INT8),
          new Column("minimumvalue", ColumnType.INT4),
          new Column("maximumvalue", ColumnType.INT4),
          new Column("generatedvalue", ColumnType.BOOLEAN));

  // -------------------------------------------------------------------------
  // DataAnalysisService implementation
  // -------------------------------------------------------------------------

  @Override
  public List<DeflatedDataValue> analyse(
      OrganisationUnit orgUnit,
      Collection<DataElement> dataElements,
      Collection<Period> periods,
      Double stdDevFactor,
      Date from) {
    Set<DataElement> elements =
        dataElements.stream()
            .filter(de -> de.getValueType().isNumeric())
            .collect(Collectors.toSet());
    Set<CategoryOptionCombo> categoryOptionCombos = new HashSet<>();

    for (DataElement dataElement : elements) {
      categoryOptionCombos.addAll(dataElement.getCategoryOptionCombos());
    }

    log.debug("Starting min-max analysis, no of data elements: {}", elements.size());

    return dataAnalysisStore.getMinMaxViolations(
        elements, categoryOptionCombos, periods, orgUnit, MAX_OUTLIERS);
  }

  @Override
  @Transactional
  public void generateMinMaxValues(
      OrganisationUnit orgUnit, Collection<DataElement> dataElements, Double stdDevFactor) {
    log.info(
        "Starting min-max value generation, data elements: {}, parent: '{}'",
        dataElements.size(),
        orgUnit.getUid());

    Date from = new DateTime(1, 1, 1, 1, 1).toDate();

    minMaxDataElementService.removeMinMaxDataElements(dataElements, orgUnit);

    log.debug("Deleted existing min-max values");

    List<Object[]> rows = new ArrayList<>();

    for (DataElement dataElement : dataElements) {
      if (dataElement.getValueType().isNumeric()) {
        Set<CategoryOptionCombo> categoryOptionCombos = dataElement.getCategoryOptionCombos();

        List<DataAnalysisMeasures> measuresList =
            dataAnalysisStore.getDataAnalysisMeasures(
                dataElement, categoryOptionCombos, orgUnit, from);

        for (DataAnalysisMeasures measures : measuresList) {
          int min =
              (int)
                  Math.round(
                      MathUtils.getLowBound(
                          measures.getStandardDeviation(), stdDevFactor, measures.getAverage()));
          int max =
              (int)
                  Math.round(
                      MathUtils.getHighBound(
                          measures.getStandardDeviation(), stdDevFactor, measures.getAverage()));

          if (POS_INT_TYPES.contains(dataElement.getValueType())) {
            min = Math.max(0, min); // Cannot be < 0
          } else if (ValueType.INTEGER_NEGATIVE == dataElement.getValueType()) {
            max = Math.min(0, max); // Cannot be > 0
          }

          rows.add(
              new Object[] {
                measures.getOrgUnitId(),
                dataElement.getId(),
                measures.getCategoryOptionComboId(),
                min,
                max,
                true
              });
        }
      }
    }

    bulkInsert.copyInto("minmaxdataelement", COLUMNS, rows);

    log.info("Min-max value generation done");
  }
}
