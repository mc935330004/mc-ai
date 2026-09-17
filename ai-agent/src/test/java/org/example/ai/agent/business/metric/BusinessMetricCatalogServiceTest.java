package org.example.ai.agent.business.metric;

import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.metric.BusinessMetricCatalogService.MetricOption;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.common.enums.protocol.ValueType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 指标编码、名称、类型和单位的配置映射测试。
 */
class BusinessMetricCatalogServiceTest {

    private final ReportDatasetMapper datasetMapper =
            mock(ReportDatasetMapper.class);

    private final ReportDatasetFieldMapper datasetFieldMapper =
            mock(ReportDatasetFieldMapper.class);

    private final FieldDictionaryMapper fieldDictionaryMapper =
            mock(FieldDictionaryMapper.class);

    private final BusinessMetricCatalogService service =
            new BusinessMetricCatalogService(
                    datasetMapper,
                    datasetFieldMapper,
                    fieldDictionaryMapper
            );

    @Test
    void selectsPublishedVisibleMetricWithConfiguredUnit() {
        when(datasetMapper.selectList(any()))
                .thenReturn(List.of(dataset()));

        when(datasetFieldMapper.selectList(any()))
                .thenReturn(List.of(datasetField()));

        when(fieldDictionaryMapper.selectBatchIds(any()))
                .thenReturn(List.of(dictionary()));

        Optional<MetricOption> selected =
                service.selectProjectMetric(
                        List.of("PROJECT_BUDGET"),
                        List.of("BUDGET"),
                        "查询项目概算中人员费用已用金额"
                );

        assertThat(selected).isPresent();

        assertThat(selected.orElseThrow())
                .satisfies(metric -> {
                    assertThat(metric.datasetCode())
                            .isEqualTo("PROJECT_BUDGET");
                    assertThat(metric.datasetName())
                            .isEqualTo("项目概算");
                    assertThat(metric.metricCode())
                            .isEqualTo("personnelExpenseUsed");
                    assertThat(metric.metricName())
                            .isEqualTo("人员费用已用金额");
                    assertThat(metric.valueType())
                            .isEqualTo(ValueType.AMOUNT);
                    assertThat(metric.unit())
                            .isEqualTo("元");
                });
    }

    @Test
    void unpublishedDictionaryCannotBecomeMetric() {
        FieldDictionary dictionary = dictionary();
        dictionary.setPublishStatus("DRAFT");

        when(datasetMapper.selectList(any()))
                .thenReturn(List.of(dataset()));

        when(datasetFieldMapper.selectList(any()))
                .thenReturn(List.of(datasetField()));

        when(fieldDictionaryMapper.selectBatchIds(any()))
                .thenReturn(List.of(dictionary));

        Optional<MetricOption> selected =
                service.selectProjectMetric(
                        List.of("PROJECT_BUDGET"),
                        List.of("BUDGET"),
                        "查询项目概算中人员费用已用金额"
                );

        assertThat(selected).isEmpty();
    }

    private ReportDataset dataset() {
        ReportDataset dataset = new ReportDataset();
        dataset.setId(1L);
        dataset.setDatasetCode("PROJECT_BUDGET");
        dataset.setDatasetName("项目概算");
        dataset.setEnabled(true);
        return dataset;
    }

    private ReportDatasetField datasetField() {
        ReportDatasetField field = new ReportDatasetField();
        field.setId(10L);
        field.setDatasetId(1L);
        field.setFieldId(100L);
        field.setFactCode("personnelExpenseUsed");
        field.setFactName("人员费用已用金额");
        field.setFactType("NUMBER");
        field.setDisplayable(true);
        field.setDisplayOrder(10);
        return field;
    }

    private FieldDictionary dictionary() {
        FieldDictionary dictionary = new FieldDictionary();
        dictionary.setId(100L);
        dictionary.setPublishStatus("PUBLISHED");
        dictionary.setUserVisible(1);
        dictionary.setDisplayFormat("amount");
        dictionary.setUnit("元");
        return dictionary;
    }
}