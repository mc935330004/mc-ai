package org.example.ai.agent.capability.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.tool.FieldMeta;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 字段元数据统一服务。
 *
 * 负责：
 * 1. 查询已发布字段字典；
 * 2. 补充字段默认配置；
 * 3. 转换统一FieldMeta。
 */
@Service
@RequiredArgsConstructor
public class FieldMetadataService {

    private static final Set<String> IMPORTANCE_VALUES =
            Set.of(
                    "HIGH",
                    "NORMAL",
                    "LOW"
            );

    private static final Set<String> COMPONENT_VALUES =
            Set.of(
                    "AUTO",
                    "METRICS",
                    "KEY_VALUE",
                    "TABLE",
                    "TREE_TABLE",
                    "GROUP_TABLE",
                    "CALLOUT",
                    "STATUS",
                    "WARNINGS",
                    "HIDDEN"
            );

    private static final Set<String> VALUE_SOURCE_VALUES =
            Set.of(
                    "RAW",
                    "CALCULATED",
                    "AGGREGATED",
                    "RULE_EVALUATED"
            );

    private static final Set<String> NUMBER_TYPES =
            Set.of(
                    "number",
                    "integer",
                    "int",
                    "long",
                    "float",
                    "double",
                    "decimal",
                    "bigdecimal",
                    "numeric"
            );

    private final FieldDictionaryMapper fieldDictionaryMapper;

    /**
     * 查询某个能力已发布的字段元数据。
     */
    public List<FieldMeta> loadPublished(
            String capabilityCode) {

        if (!StringUtils.hasText(capabilityCode)) {
            return List.of();
        }

        return fieldDictionaryMapper.selectList(
                        new LambdaQueryWrapper<FieldDictionary>()
                                .eq(
                                        FieldDictionary::getCapabilityCode,
                                        capabilityCode.trim()
                                )
                                .eq(
                                        FieldDictionary::getPublishStatus,
                                        "PUBLISHED"
                                )
                                .orderByAsc(
                                        FieldDictionary::getDisplayOrder
                                )
                                .orderByAsc(
                                        FieldDictionary::getId
                                )
                )
                .stream()
                .map(this::toFieldMeta)
                .toList();
    }

    /**
     * 保存字段字典前补充默认值。
     *
     * 不根据大模型猜测业务单位和业务含义。
     */
    public FieldDictionary applyDefaults(
            FieldDictionary field) {

        if (field == null) {
            return null;
        }

        field.setFieldCode(
                firstText(
                        field.getFieldCode(),
                        field.getFieldName()
                )
        );

        field.setImportance(
                normalizeChoice(
                        field.getImportance(),
                        IMPORTANCE_VALUES,
                        "NORMAL"
                )
        );

        field.setDisplayComponent(
                normalizeChoice(
                        field.getDisplayComponent(),
                        COMPONENT_VALUES,
                        "AUTO"
                )
        );

        field.setSummaryFlag(
                isBinary(field.getSummaryFlag())
                        ? field.getSummaryFlag()
                        : 0
        );

        field.setUnit(
                trimToNull(field.getUnit())
        );

        field.setPrecisionScale(
                normalizePrecision(
                        field.getPrecisionScale()
                )
        );

        field.setValueSource(
                normalizeChoice(
                        field.getValueSource(),
                        VALUE_SOURCE_VALUES,
                        "RAW"
                )
        );

        return field;
    }

    /**
     * 将字段字典转换为回答链路使用的统一元数据。
     */
    public FieldMeta toFieldMeta(
            FieldDictionary dictionary) {

        if (dictionary == null) {
            throw new IllegalArgumentException(
                    "字段字典不能为空"
            );
        }

        applyDefaults(dictionary);

        int summaryFlag =
                resolveSummaryFlag(dictionary);

        return FieldMeta.builder()
                .name(dictionary.getFieldName())
                .fieldCode(dictionary.getFieldCode())
                .cnName(dictionary.getFieldCnName())
                .path(dictionary.getFieldPath())
                .type(dictionary.getFieldType())
                .format(dictionary.getDisplayFormat())
                .enumMappingJson(
                        dictionary.getEnumMappingJson()
                )
                .meaning(dictionary.getBusinessMeaning())
                .requiredOutput(
                        defaultInteger(
                                dictionary.getRequiredOutput(),
                                0
                        )
                )
                .modelVisible(
                        defaultInteger(
                                dictionary.getModelVisible(),
                                1
                        )
                )
                .userVisible(
                        defaultInteger(
                                dictionary.getUserVisible(),
                                1
                        )
                )
                .displayOrder(
                        defaultInteger(
                                dictionary.getDisplayOrder(),
                                0
                        )
                )
                .displayGroup(
                        dictionary.getDisplayGroup()
                )
                .nullDisplayText(
                        dictionary.getNullDisplayText()
                )
                .importance(
                        resolveImportance(dictionary)
                )
                .displayComponent(
                        resolveDisplayComponent(
                                dictionary,
                                summaryFlag
                        )
                )
                .summaryFlag(summaryFlag)
                .unit(dictionary.getUnit())
                .precisionScale(
                        dictionary.getPrecisionScale()
                )
                .valueSource(
                        dictionary.getValueSource()
                )
                .build();
    }

    /**
     * 必答字段优先作为高重要字段。
     */
    private String resolveImportance(
            FieldDictionary field) {

        if (Integer.valueOf(1).equals(
                field.getRequiredOutput())) {
            return "HIGH";
        }

        return field.getImportance();
    }

    /**
     * 汇总标记没有人工开启时，
     * 数字、金额、比例和必答字段自动进入汇总候选。
     */
    private int resolveSummaryFlag(FieldDictionary field) {

        if (Integer.valueOf(1).equals(
                field.getSummaryFlag())) {
            return 1;
        }

        if (Integer.valueOf(1).equals(
                field.getRequiredOutput())) {
            return 1;
        }

        return isNumericField(field)
                ? 1
                : 0;
    }

    /**
     * AUTO模式下根据确定性字段规则选择组件。
     */
    private String resolveDisplayComponent(
            FieldDictionary field,
            int summaryFlag) {

        String configured =
                field.getDisplayComponent();

        if (!"AUTO".equals(configured)) {
            return configured;
        }

        if (Integer.valueOf(0).equals(
                field.getUserVisible())) {
            return "HIDDEN";
        }

        String format =
                normalize(field.getDisplayFormat());

        if ("status".equals(format)
                || "enum".equals(format)) {
            return "STATUS";
        }

        if (summaryFlag == 1
                && isNumericField(field)) {
            return "METRICS";
        }

        return "AUTO";
    }

    /**
     * 判断是否为数字、金额或比例字段。
     */
    private boolean isNumericField(
            FieldDictionary field) {

        String type =
                normalize(field.getFieldType());

        String format =
                normalize(field.getDisplayFormat());

        return NUMBER_TYPES.contains(type)
                || "number".equals(format)
                || "amount".equals(format)
                || "money".equals(format)
                || "percent".equals(format);
    }

    /**
     * 数字精度只允许0到8位。
     *
     * 未配置时保持为空，不猜测业务精度。
     */
    private Integer normalizePrecision(
            Integer precisionScale) {

        if (precisionScale == null) {
            return null;
        }

        if (precisionScale < 0
                || precisionScale > 8) {
            return null;
        }

        return precisionScale;
    }

    private String normalizeChoice(
            String value,
            Set<String> allowed,
            String defaultValue) {

        if (!StringUtils.hasText(value)) {
            return defaultValue;
        }

        String normalized =
                value.trim()
                        .toUpperCase(Locale.ROOT);

        return allowed.contains(normalized)
                ? normalized
                : defaultValue;
    }

    private boolean isBinary(
            Integer value) {

        return Integer.valueOf(0).equals(value)
                || Integer.valueOf(1).equals(value);
    }

    private int defaultInteger(
            Integer value,
            int defaultValue) {

        return value == null
                ? defaultValue
                : value;
    }

    private String firstText(
            String... values) {

        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }

        return "";
    }

    private String trimToNull(
            String value) {

        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }

    private String normalize(
            String value) {

        return StringUtils.hasText(value)
                ? value.trim()
                .toLowerCase(Locale.ROOT)
                : "";
    }
}