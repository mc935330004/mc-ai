package org.example.ai.agent.business.metric;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.common.enums.protocol.ValueType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 从当前已启用数据集和已发布字段字典中读取可展示指标。
 *
 * 指标编码直接使用 ReportDatasetField.factCode，
 * 不允许模型临时创造指标编码、类型或单位。
 */
@Service
@RequiredArgsConstructor
public class BusinessMetricCatalogService {

    private final ReportDatasetMapper datasetMapper;
    private final ReportDatasetFieldMapper datasetFieldMapper;
    private final FieldDictionaryMapper fieldDictionaryMapper;

    /**
     * 从项目允许的数据集中匹配用户明确点名的单个指标。
     *
     * 匹配不到或同时匹配多个指标时返回空，
     * 上层统一提示“该指标尚未配置”，不能猜测。
     */
    public Optional<MetricOption> selectProjectMetric(
            List<String> availableDatasetCodes,
            List<String> requestedDatasetCodes,
            String question) {
        List<MetricOption> options =
                loadOptions(availableDatasetCodes);

        if (options.isEmpty()) {
            return Optional.empty();
        }

        List<MetricOption> scoped =
                narrowByDataset(options, requestedDatasetCodes, question);

        String normalizedQuestion = normalizeSearchText(question);
        List<MetricOption> matched = scoped.stream()
                .filter(option -> matchesMetric(
                        option,
                        normalizedQuestion
                ))
                .toList();

        if (matched.isEmpty()) {
            return Optional.empty();
        }

        /*
         * “人员费用”和“人员费用已用金额”同时命中时，
         * 优先选择名称更具体的指标。
         */
        int longestName = matched.stream()
                .mapToInt(option ->
                        normalizeSearchText(option.metricName()).length())
                .max()
                .orElse(0);

        List<MetricOption> best = matched.stream()
                .filter(option ->
                        normalizeSearchText(option.metricName()).length()
                                == longestName)
                .distinct()
                .toList();

        return best.size() == 1
                ? Optional.of(best.get(0))
                : Optional.empty();
    }

    private List<MetricOption> loadOptions(
            List<String> availableDatasetCodes) {
        if (availableDatasetCodes == null
                || availableDatasetCodes.isEmpty()) {
            return List.of();
        }

        List<ReportDataset> datasets = datasetMapper.selectList(
                Wrappers.<ReportDataset>lambdaQuery()
                        .in(
                                ReportDataset::getDatasetCode,
                                availableDatasetCodes
                        )
                        .eq(ReportDataset::getEnabled, true)
        );

        if (datasets == null || datasets.isEmpty()) {
            return List.of();
        }

        Map<Long, ReportDataset> datasetsById = new LinkedHashMap<>();
        for (ReportDataset dataset : datasets) {
            if (dataset == null || dataset.getId() == null) {
                continue;
            }
            datasetsById.put(dataset.getId(), dataset);
        }
        if (datasetsById.isEmpty()) {
            return List.of();
        }

        List<ReportDatasetField> fields =
                datasetFieldMapper.selectList(
                        Wrappers.<ReportDatasetField>lambdaQuery().in(
                                ReportDatasetField::getDatasetId, datasetsById.keySet())
                                .orderByAsc(ReportDatasetField::getDisplayOrder, ReportDatasetField::getId));

        if (fields == null || fields.isEmpty()) {
            return List.of();
        }

        Set<Long> dictionaryIds = new LinkedHashSet<>();
        for (ReportDatasetField field : fields) {
            if (field != null && field.getFieldId() != null) {
                dictionaryIds.add(field.getFieldId());
            }
        }

        if (dictionaryIds.isEmpty()) {
            return List.of();
        }

        List<FieldDictionary> dictionaries =
                fieldDictionaryMapper.selectBatchIds(dictionaryIds);

        Map<Long, FieldDictionary> dictionariesById =
                new LinkedHashMap<>();

        if (dictionaries != null) {
            for (FieldDictionary dictionary : dictionaries) {
                if (dictionary == null || dictionary.getId() == null) {
                    continue;
                }
                dictionariesById.put(dictionary.getId(), dictionary);
            }
        }

        List<MetricOption> result = new ArrayList<>();

        for (ReportDatasetField field : fields) {
            MetricOption option = toOption(
                    field,
                    datasetsById,
                    dictionariesById
            );

            if (option != null) {
                result.add(option);
            }
        }

        return List.copyOf(result);
    }

    private MetricOption toOption(
            ReportDatasetField field,
            Map<Long, ReportDataset> datasetsById,
            Map<Long, FieldDictionary> dictionariesById) {
        if (field == null
                || !Boolean.TRUE.equals(field.getDisplayable())
                || !isScalarType(field.getFactType())
                || !StringUtils.hasText(field.getFactCode())
                || !StringUtils.hasText(field.getFactName())) {
            return null;
        }

        ReportDataset dataset =
                datasetsById.get(field.getDatasetId());

        FieldDictionary dictionary =
                dictionariesById.get(field.getFieldId());

        if (dataset == null
                || dictionary == null
                || !"PUBLISHED".equals(dictionary.getPublishStatus())
                || !Integer.valueOf(1).equals(
                        dictionary.getUserVisible()
                )) {
            return null;
        }

        return new MetricOption(
                dataset.getDatasetCode(),
                displayName(
                        dataset.getDatasetName(),
                        dataset.getDatasetCode()
                ),
                field.getFactCode(),
                field.getFactName(),
                valueType(field.getFactType(),
                        dictionary.getDisplayFormat()),
                normalize(dictionary.getUnit())
        );
    }

    private List<MetricOption> narrowByDataset(
            List<MetricOption> options,
            List<String> requestedDatasetCodes,
            String question) {
        Set<String> requestedCodes = new LinkedHashSet<>();

        if (requestedDatasetCodes != null) {
            requestedDatasetCodes.stream()
                    .filter(StringUtils::hasText)
                    .map(code ->
                            code.trim().toUpperCase(Locale.ROOT))
                    .forEach(requestedCodes::add);
        }

        List<MetricOption> codeMatched = options.stream()
                .filter(option ->
                        requestedCodes.stream().anyMatch(code ->
                                matchesDatasetCode(
                                        option.datasetCode(),
                                        code
                                )))
                .toList();

        if (!codeMatched.isEmpty()) {
            return codeMatched;
        }

        String normalizedQuestion = normalizeSearchText(question);
        List<MetricOption> nameMatched = options.stream()
                .filter(option -> {
                    String datasetName =
                            normalizeSearchText(option.datasetName());

                    return datasetName.length() >= 2
                            && normalizedQuestion.contains(datasetName);
                })
                .toList();

        return nameMatched.isEmpty()
                ? options
                : nameMatched;
    }

    private boolean matchesDatasetCode(
            String configuredCode,
            String requestedCode) {
        String configured =
                configuredCode.toUpperCase(Locale.ROOT);

        return configured.equals(requestedCode)
                || configured.endsWith("_" + requestedCode)
                || requestedCode.endsWith("_" + configured);
    }

    private boolean matchesMetric(
            MetricOption option,
            String normalizedQuestion) {
        String metricName =
                normalizeSearchText(option.metricName());

        String metricCode =
                normalizeSearchText(option.metricCode());

        return metricName.length() >= 2
                && normalizedQuestion.contains(metricName)
                || metricCode.length() >= 2
                && normalizedQuestion.contains(metricCode);
    }

    private boolean isScalarType(String factType) {
        String type = normalizeUpper(factType);
        return !"ARRAY".equals(type)
                && !"OBJECT".equals(type);
    }

    private ValueType valueType(
            String factType,
            String displayFormat) {
        String format = normalizeUpper(displayFormat);

        return switch (format) {
            case "AMOUNT", "MONEY" -> ValueType.AMOUNT;
            case "PERCENT" -> ValueType.PERCENT;
            case "DATE" -> ValueType.DATE;
            case "DATETIME" -> ValueType.DATETIME;
            case "STATUS", "ENUM" -> ValueType.ENUM;
            case "BOOLEAN" -> ValueType.BOOLEAN;
            case "NUMBER" -> ValueType.NUMBER;
            default -> valueTypeFromFactType(factType);
        };
    }

    private ValueType valueTypeFromFactType(String factType) {
        return switch (normalizeUpper(factType)) {
            case "NUMBER", "INTEGER", "LONG",
                 "DECIMAL", "DOUBLE" -> ValueType.NUMBER;
            case "BOOLEAN" -> ValueType.BOOLEAN;
            case "DATE" -> ValueType.DATE;
            case "DATETIME" -> ValueType.DATETIME;
            default -> ValueType.TEXT;
        };
    }

    private String displayName(
            String preferred,
            String fallback) {
        return StringUtils.hasText(preferred)
                ? preferred.trim()
                : fallback.trim();
    }

    private String normalize(String value) {
        return StringUtils.hasText(value)
                ? value.trim()
                : "";
    }

    private String normalizeUpper(String value) {
        return StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : "";
    }

    private String normalizeSearchText(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        return value
                .replaceAll(
                        "[\\s\\p{Punct}，。；：、“”‘’（）【】]+",
                        ""
                )
                .toLowerCase(Locale.ROOT);
    }

    /**
     * 单指标的确定性展示配置。
     */
    public record MetricOption(
            String datasetCode,
            String datasetName,
            String metricCode,
            String metricName,
            ValueType valueType,
            String unit) {
    }
}