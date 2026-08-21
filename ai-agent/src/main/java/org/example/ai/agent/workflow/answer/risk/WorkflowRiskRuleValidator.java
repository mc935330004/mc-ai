package org.example.ai.agent.workflow.answer.risk;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.common.enums.GraphNodeType;
import org.example.ai.agent.graph.compiler.GraphValidationError;
import org.example.ai.agent.graph.model.GraphNodeSpec;
import org.example.ai.agent.graph.model.GraphSpec;
import org.example.ai.agent.graph.model.risk.WorkflowRiskConditionSpec;
import org.example.ai.agent.graph.model.risk.WorkflowRiskConditionSpec.RiskOperator;
import org.example.ai.agent.graph.model.risk.WorkflowRiskConditionSpec.RiskRightType;
import org.example.ai.agent.graph.model.risk.WorkflowRiskRuleSpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 校验工作流风险规则。
 *
 * 规则只能使用当前工作流能力的已发布可见字段，
 * 路径必须与字段字典完全一致。
 */
@Component
@RequiredArgsConstructor
public class WorkflowRiskRuleValidator {

    private static final int MAX_RULE_COUNT = 30;
    private static final int MAX_CONDITION_COUNT = 10;

    private static final Pattern RULE_CODE =
            Pattern.compile("^[A-Za-z_][A-Za-z0-9_-]{0,63}$");

    private static final Pattern SAFE_PATH =
            Pattern.compile(
                    "^(\\$\\.)?[A-Za-z_][A-Za-z0-9_]*(\\[\\])?"
                            + "(\\.[A-Za-z_][A-Za-z0-9_]*(\\[\\])?)*$"
            );

    private static final Set<String> NUMBER_FIELD_TYPES = Set.of(
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
    private static final Set<RiskOperator> NUMBER_CONSTANT_OPERATORS = Set.of(
            RiskOperator.EQ,
            RiskOperator.NE,
            RiskOperator.GT,
            RiskOperator.GTE,
            RiskOperator.LT,
            RiskOperator.LTE,
            RiskOperator.IN,
            RiskOperator.NOT_IN
    );

    private static final Set<RiskOperator> NUMBER_OPERATORS = Set.of(
            RiskOperator.GT,
            RiskOperator.GTE,
            RiskOperator.LT,
            RiskOperator.LTE
    );

    private static final Set<RiskOperator> NULL_OPERATORS = Set.of(
            RiskOperator.IS_NULL,
            RiskOperator.NOT_NULL
    );

    private final FieldDictionaryMapper fieldDictionaryMapper;

    /**
     * 返回全部风险规则校验错误。
     */
    public List<GraphValidationError> validate(GraphSpec graph) {
        if (graph == null
                || graph.getRiskRules() == null
                || graph.getRiskRules().isEmpty()) {
            return List.of();
        }

        List<GraphValidationError> errors = new ArrayList<>();
        List<WorkflowRiskRuleSpec> rules = graph.getRiskRules();

        if (rules.size() > MAX_RULE_COUNT) {
            errors.add(error(
                    "RISK_RULE_COUNT_EXCEEDED",
                    "root.riskRules",
                    "工作流风险规则最多允许30条"
            ));
        }

        Set<String> capabilityCodes = collectCapabilityCodes(graph);
        Set<Long> fieldIds = collectFieldIds(rules);
        Map<Long, FieldDictionary> dictionaries;

        try {
            dictionaries = loadDictionaries(fieldIds);
        } catch (RuntimeException exception) {
            errors.add(error(
                    "RISK_FIELD_DICTIONARY_UNAVAILABLE",
                    "root.riskRules",
                    "风险规则字段字典暂时不可用"
            ));
            return errors;
        }

        Set<String> ruleCodes = new HashSet<>();

        for (int index = 0; index < rules.size(); index++) {
            validateRule(
                    rules.get(index),
                    index,
                    ruleCodes,
                    capabilityCodes,
                    dictionaries,
                    errors
            );
        }

        return errors;
    }

    private void validateRule(
            WorkflowRiskRuleSpec rule,
            int ruleIndex,
            Set<String> ruleCodes,
            Set<String> capabilityCodes,
            Map<Long, FieldDictionary> dictionaries,
            List<GraphValidationError> errors) {

        String rulePath = "root.riskRules[" + ruleIndex + "]";

        if (rule == null) {
            errors.add(error(
                    "RISK_RULE_REQUIRED",
                    rulePath,
                    "风险规则不能为空"
            ));
            return;
        }

        if (!StringUtils.hasText(rule.code())
                || !RULE_CODE.matcher(rule.code()).matches()) {
            errors.add(error(
                    "RISK_RULE_CODE_INVALID",
                    rulePath + ".code",
                    "规则编码必须以字母或下划线开头，只允许字母、数字、下划线和连接符"
            ));
        } else if (!ruleCodes.add(rule.code())) {
            errors.add(error(
                    "RISK_RULE_CODE_DUPLICATED",
                    rulePath + ".code",
                    "规则编码不能重复：" + rule.code()
            ));
        }

        if (!StringUtils.hasText(rule.name())) {
            errors.add(error(
                    "RISK_RULE_NAME_REQUIRED",
                    rulePath + ".name",
                    "规则名称不能为空"
            ));
        }

        /*
         * 未启用规则允许暂时保留未完成配置，
         * 但规则编码仍然必须合法且唯一。
         */
        if (!rule.enabled()) {
            return;
        }

        if (rule.severity() == null) {
            errors.add(error(
                    "RISK_RULE_SEVERITY_REQUIRED",
                    rulePath + ".severity",
                    "风险等级不能为空"
            ));
        }

        if (rule.logic() == null) {
            errors.add(error(
                    "RISK_RULE_LOGIC_REQUIRED",
                    rulePath + ".logic",
                    "条件组合方式不能为空"
            ));
        }

        if (!isSafePath(rule.objectPath())) {
            errors.add(error(
                    "RISK_OBJECT_PATH_INVALID",
                    rulePath + ".objectPath",
                    "风险判断对象路径不合法"
            ));
        }

        FieldDictionary objectKey = requireField(
                rule.objectKeyFieldId(),
                rulePath + ".objectKeyFieldId",
                capabilityCodes,
                dictionaries,
                errors
        );

        validateConfiguredPath(
                objectKey,
                rule.objectPath(),
                true,
                rulePath + ".objectPath",
                errors
        );

        List<WorkflowRiskConditionSpec> conditions = rule.conditions();

        if (conditions == null || conditions.isEmpty()) {
            errors.add(error(
                    "RISK_CONDITION_REQUIRED",
                    rulePath + ".conditions",
                    "已启用风险规则至少需要一个条件"
            ));
            return;
        }

        if (conditions.size() > MAX_CONDITION_COUNT) {
            errors.add(error(
                    "RISK_CONDITION_COUNT_EXCEEDED",
                    rulePath + ".conditions",
                    "每条风险规则最多允许10个条件"
            ));
        }

        for (int conditionIndex = 0;
             conditionIndex < conditions.size();
             conditionIndex++) {

            validateCondition(
                    conditions.get(conditionIndex),
                    rule.objectPath(),
                    rulePath + ".conditions[" + conditionIndex + "]",
                    capabilityCodes,
                    dictionaries,
                    errors
            );
        }
    }

    private void validateCondition(
            WorkflowRiskConditionSpec condition,
            String objectPath,
            String conditionPath,
            Set<String> capabilityCodes,
            Map<Long, FieldDictionary> dictionaries,
            List<GraphValidationError> errors) {

        if (condition == null) {
            errors.add(error(
                    "RISK_CONDITION_INVALID",
                    conditionPath,
                    "风险条件不能为空"
            ));
            return;
        }

        if (condition.operator() == null) {
            errors.add(error(
                    "RISK_OPERATOR_REQUIRED",
                    conditionPath + ".operator",
                    "风险条件运算符不能为空"
            ));
            return;
        }

        FieldDictionary leftField = requireField(
                condition.leftFieldId(),
                conditionPath + ".leftFieldId",
                capabilityCodes,
                dictionaries,
                errors
        );

        validateConfiguredPath(
                leftField,
                condition.leftPath(),
                false,
                conditionPath + ".leftPath",
                errors
        );

        validateObjectScope(
                condition.leftPath(),
                objectPath,
                conditionPath + ".leftPath",
                errors
        );

        if (NULL_OPERATORS.contains(condition.operator())) {
            validateNullOperator(condition, conditionPath, errors);
            return;
        }

        if (condition.rightType() == RiskRightType.FIELD) {
            validateFieldRightValue(
                    condition,
                    leftField,
                    objectPath,
                    conditionPath,
                    capabilityCodes,
                    dictionaries,
                    errors
            );
        } else {
            validateConstantRightValue(
                    condition,
                    leftField,
                    conditionPath,
                    errors
            );
        }
    }

    private void validateNullOperator(
            WorkflowRiskConditionSpec condition,
            String conditionPath,
            List<GraphValidationError> errors) {

        if (StringUtils.hasText(condition.constantValue())
                || condition.rightFieldId() != null
                || StringUtils.hasText(condition.rightPath())) {

            errors.add(error(
                    "RISK_NULL_OPERATOR_RIGHT_VALUE_FORBIDDEN",
                    conditionPath,
                    "IS_NULL和NOT_NULL不能配置右侧比较值"
            ));
        }
    }

    private void validateFieldRightValue(
            WorkflowRiskConditionSpec condition,
            FieldDictionary leftField,
            String objectPath,
            String conditionPath,
            Set<String> capabilityCodes,
            Map<Long, FieldDictionary> dictionaries,
            List<GraphValidationError> errors) {

        if (StringUtils.hasText(condition.constantValue())) {
            errors.add(error(
                    "RISK_FIELD_RIGHT_CONSTANT_FORBIDDEN",
                    conditionPath + ".constantValue",
                    "字段比较不能同时配置常量值"
            ));
        }

        FieldDictionary rightField = requireField(
                condition.rightFieldId(),
                conditionPath + ".rightFieldId",
                capabilityCodes,
                dictionaries,
                errors
        );

        validateConfiguredPath(
                rightField,
                condition.rightPath(),
                false,
                conditionPath + ".rightPath",
                errors
        );

        validateObjectScope(
                condition.rightPath(),
                objectPath,
                conditionPath + ".rightPath",
                errors
        );

        if (leftField != null
                && rightField != null
                && !typesCompatible(leftField, rightField)) {

            errors.add(error(
                    "RISK_FIELD_TYPE_INCOMPATIBLE",
                    conditionPath,
                    "左右字段类型不兼容"
            ));
        }

        if (NUMBER_OPERATORS.contains(condition.operator())
                && (!isNumberField(leftField)
                || !isNumberField(rightField))) {

            errors.add(error(
                    "RISK_NUMBER_FIELD_REQUIRED",
                    conditionPath,
                    "大于、小于类运算符只能比较数字字段"
            ));
        }
    }

    private void validateConstantRightValue(
            WorkflowRiskConditionSpec condition,
            FieldDictionary leftField,
            String conditionPath,
            List<GraphValidationError> errors) {

        if (!StringUtils.hasText(condition.constantValue())) {
            errors.add(error(
                    "RISK_CONSTANT_REQUIRED",
                    conditionPath + ".constantValue",
                    "常量比较必须填写比较值"
            ));
            return;
        }

        if (condition.rightFieldId() != null
                || StringUtils.hasText(condition.rightPath())) {

            errors.add(error(
                    "RISK_CONSTANT_FIELD_FORBIDDEN",
                    conditionPath,
                    "常量比较不能同时配置右侧字段"
            ));
        }
        if (NUMBER_OPERATORS.contains(condition.operator()) && !isNumberField(leftField)) {
            errors.add(error(
                    "RISK_NUMBER_FIELD_REQUIRED",
                    conditionPath + ".leftFieldId",
                    "大于、小于类运算符只能用于数字字段"
            ));
            return;
        }

        /*
         * 数字字段参与等于、不等于、范围或集合比较时，
         * 所有常量都必须能够转换为BigDecimal。
         */
        if (isNumberField(leftField) && NUMBER_CONSTANT_OPERATORS.contains(condition.operator()) &&
                !isValidNumberConstant(condition.constantValue(), condition.operator())) {
            errors.add(error(
                    "RISK_NUMBER_CONSTANT_INVALID",
                    conditionPath + ".constantValue",
                    "数字字段的比较值必须是有效数字"
            ));
        }
    }

    private boolean isValidNumberConstant(String constantValue, RiskOperator operator) {
        if (!StringUtils.hasText(constantValue)) {
            return false;
        }
        String[] values = operator == RiskOperator.IN || operator == RiskOperator.NOT_IN ? constantValue.split("[,，]") : new String[]{constantValue};

        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                return false;
            }
            try {
                new BigDecimal(value.trim());
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }


    private FieldDictionary requireField(
            Long fieldId,
            String graphPath,
            Set<String> capabilityCodes,
            Map<Long, FieldDictionary> dictionaries,
            List<GraphValidationError> errors) {

        if (fieldId == null || fieldId <= 0) {
            errors.add(error(
                    "RISK_FIELD_ID_REQUIRED",
                    graphPath,
                    "风险规则字段ID不能为空"
            ));
            return null;
        }

        FieldDictionary field = dictionaries.get(fieldId);

        if (field == null) {
            errors.add(error(
                    "RISK_FIELD_NOT_FOUND",
                    graphPath,
                    "风险规则字段字典不存在：" + fieldId
            ));
            return null;
        }

        if (!"PUBLISHED".equalsIgnoreCase(field.getPublishStatus())) {
            errors.add(error(
                    "RISK_FIELD_NOT_PUBLISHED",
                    graphPath,
                    "风险规则只能使用已发布字段：" + fieldId
            ));
        }

        if (!capabilityCodes.contains(field.getCapabilityCode())) {
            errors.add(error(
                    "RISK_FIELD_OUT_OF_WORKFLOW",
                    graphPath,
                    "风险规则字段不属于当前工作流能力：" + fieldId
            ));
        }

        if (Integer.valueOf(0).equals(field.getVisible())) {
            errors.add(error(
                    "RISK_FIELD_NOT_VISIBLE",
                    graphPath,
                    "风险规则不能使用禁止展示的字段：" + fieldId
            ));
        }

        return field;
    }

    private void validateConfiguredPath(
            FieldDictionary field,
            String configuredPath,
            boolean objectPath,
            String graphPath,
            List<GraphValidationError> errors) {

        if (field == null) {
            return;
        }

        String dictionaryPath = trimToNull(field.getFieldPath());

        String expectedPath = objectPath
                ? resolveObjectPath(dictionaryPath)
                : dictionaryPath;

        if (!Objects.equals(expectedPath, trimToNull(configuredPath))) {
            errors.add(error(
                    "RISK_FIELD_PATH_MISMATCH",
                    graphPath,
                    "风险规则路径与字段字典不一致"
            ));
        }
    }

    private void validateObjectScope(
            String fieldPath,
            String objectPath,
            String graphPath,
            List<GraphValidationError> errors) {

        if (!Objects.equals(
                resolveObjectPath(fieldPath),
                trimToNull(objectPath))) {

            errors.add(error(
                    "RISK_FIELD_SCOPE_MISMATCH",
                    graphPath,
                    "条件字段必须与判断对象处于同一数组作用域"
            ));
        }
    }

    /**
     * 使用最深层数组作为当前风险判断对象。
     */
    private String resolveObjectPath(String fieldPath) {
        String normalized = trimToNull(fieldPath);

        if (normalized == null) {
            return null;
        }

        int arrayIndex = normalized.lastIndexOf("[]");

        return arrayIndex < 0
                ? null
                : normalized.substring(0, arrayIndex + 2);
    }

    private Set<Long> collectFieldIds(
            List<WorkflowRiskRuleSpec> rules) {

        Set<Long> fieldIds = new LinkedHashSet<>();

        for (WorkflowRiskRuleSpec rule : rules) {
            if (rule == null || !rule.enabled()) {
                continue;
            }

            addFieldId(fieldIds, rule.objectKeyFieldId());

            for (WorkflowRiskConditionSpec condition : rule.conditions()) {
                if (condition == null) {
                    continue;
                }

                addFieldId(fieldIds, condition.leftFieldId());

                if (condition.rightType() == RiskRightType.FIELD) {
                    addFieldId(fieldIds, condition.rightFieldId());
                }
            }
        }

        return fieldIds;
    }

    private void addFieldId(Set<Long> fieldIds, Long fieldId) {
        if (fieldId != null && fieldId > 0) {
            fieldIds.add(fieldId);
        }
    }

    private Map<Long, FieldDictionary> loadDictionaries(
            Set<Long> fieldIds) {

        if (fieldIds.isEmpty()) {
            return Map.of();
        }

        List<FieldDictionary> dictionaries =
                fieldDictionaryMapper.selectBatchIds(fieldIds);

        Map<Long, FieldDictionary> result = new HashMap<>();

        if (dictionaries != null) {
            for (FieldDictionary dictionary : dictionaries) {
                if (dictionary != null && dictionary.getId() != null) {
                    result.put(dictionary.getId(), dictionary);
                }
            }
        }

        return result;
    }

    private Set<String> collectCapabilityCodes(GraphSpec graph) {
        Set<String> capabilityCodes = new LinkedHashSet<>();

        if (graph.getNodes() == null) {
            return capabilityCodes;
        }

        for (GraphNodeSpec node : graph.getNodes()) {
            if (node == null || node.getConfig() == null) {
                continue;
            }

            if (node.getType() == GraphNodeType.CAPABILITY) {
                addCapabilityCode(
                        node.getConfig().path("capabilityCode"),
                        capabilityCodes
                );
            }

            if (node.getType() == GraphNodeType.FOREACH) {
                collectNestedCapabilities(
                        node.getConfig().path("body"),
                        capabilityCodes
                );
            }
        }

        return capabilityCodes;
    }

    private void collectNestedCapabilities(
            JsonNode graphNode,
            Set<String> capabilityCodes) {

        JsonNode nodes = graphNode.path("nodes");

        if (!nodes.isArray()) {
            return;
        }

        for (JsonNode node : nodes) {
            String type = node.path("type").asText();
            JsonNode config = node.path("config");

            if ("CAPABILITY".equals(type)) {
                addCapabilityCode(
                        config.path("capabilityCode"),
                        capabilityCodes
                );
            }

            if ("FOREACH".equals(type)) {
                collectNestedCapabilities(
                        config.path("body"),
                        capabilityCodes
                );
            }
        }
    }

    private void addCapabilityCode(
            JsonNode codeNode,
            Set<String> capabilityCodes) {

        String code = codeNode.asText(null);

        if (StringUtils.hasText(code)) {
            capabilityCodes.add(code.trim());
        }
    }

    private boolean typesCompatible(FieldDictionary left, FieldDictionary right) {
        String leftType = normalizeFieldType(left);
        String rightType = normalizeFieldType(right);
        /*
         * 字段没有声明类型时不能判定为兼容，
         * 应通过发布校验提示管理员补充字段类型。
         */
        if (leftType == null || rightType == null) {
            return false;
        }
        if (NUMBER_FIELD_TYPES.contains(leftType) || NUMBER_FIELD_TYPES.contains(rightType)) {
            return NUMBER_FIELD_TYPES.contains(leftType) && NUMBER_FIELD_TYPES.contains(rightType);
        }
        return Objects.equals(leftType, rightType);
    }

    private boolean isNumberField(FieldDictionary field) {
        String fieldType = normalizeFieldType(field);

        return fieldType != null
                && NUMBER_FIELD_TYPES.contains(fieldType);
    }

    private String normalizeFieldType(FieldDictionary field) {
        if (field == null
                || !StringUtils.hasText(field.getFieldType())) {
            return null;
        }

        return field.getFieldType()
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private boolean isSafePath(String path) {
        return StringUtils.hasText(path)
                && SAFE_PATH.matcher(path.trim()).matches();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }

    private GraphValidationError error(
            String code,
            String graphPath,
            String message) {

        return new GraphValidationError(
                code,
                graphPath,
                null,
                null,
                message
        );
    }
}