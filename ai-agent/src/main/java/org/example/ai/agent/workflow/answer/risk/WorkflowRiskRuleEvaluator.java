package org.example.ai.agent.workflow.answer.risk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.invocation.runtime.SimpleJsonPathReader;
import org.example.ai.agent.graph.model.risk.WorkflowRiskConditionSpec;
import org.example.ai.agent.graph.model.risk.WorkflowRiskConditionSpec.RiskOperator;
import org.example.ai.agent.graph.model.risk.WorkflowRiskConditionSpec.RiskRightType;
import org.example.ai.agent.graph.model.risk.WorkflowRiskRuleSpec;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldContext;
import org.example.ai.agent.workflow.answer.WorkflowAnswerPreparation;
import org.example.ai.agent.workflow.answer.risk.WorkflowRiskEvaluation.Evidence;
import org.example.ai.agent.workflow.answer.risk.WorkflowRiskEvaluation.Status;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用工作流发布规则执行确定性风险判定。
 *
 * 不调用大模型，不猜测缺失字段，
 * 所有金额比较使用BigDecimal。
 */
@Service
@RequiredArgsConstructor
public class WorkflowRiskRuleEvaluator {

    private static final int MAX_EVIDENCE_VALUE_LENGTH = 120;

    private final ObjectMapper objectMapper;
    private final SimpleJsonPathReader jsonPathReader;
    private final WorkflowRiskRuleResolver ruleResolver;

    /**
     * 对安全投影结果执行全部已启用规则。
     */
    public List<WorkflowRiskEvaluation> evaluate(
            WorkflowAnswerPreparation preparation) {

        if (preparation == null) {
            throw new IllegalArgumentException(
                    "工作流回答准备结果不能为空"
            );
        }

        List<WorkflowRiskRuleSpec> rules =
                ruleResolver.resolve(preparation.outcome());

        if (rules.isEmpty()) {
            return List.of();
        }

        JsonNode resultRoot = objectMapper.valueToTree(
                preparation.modelPayload().result()
        );

        List<JsonNode> candidateRoots =
                buildCandidateRoots(resultRoot);

        Map<String, WorkflowAnswerFieldContext> fieldsByPath =
                buildFieldContextMap(preparation);

        List<WorkflowRiskEvaluation> evaluations =
                new ArrayList<>();

        for (WorkflowRiskRuleSpec rule : rules) {
            List<JsonNode> objects =
                    readRuleObjects(
                            candidateRoots,
                            rule.objectPath()
                    );

            if (objects.isEmpty()) {
                evaluations.add(new WorkflowRiskEvaluation(
                        rule.code(),
                        rule.name(),
                        rule.severity(),
                        null,
                        Status.UNKNOWN,
                        List.of(),
                        "判断对象路径不存在或没有返回业务对象"
                ));
                continue;
            }

            for (JsonNode object : objects) {
                evaluations.add(
                        evaluateObject(
                                rule,
                                object,
                                fieldsByPath
                        )
                );
            }
        }

        return List.copyOf(evaluations);
    }

    private WorkflowRiskEvaluation evaluateObject(
            WorkflowRiskRuleSpec rule,
            JsonNode object,
            Map<String, WorkflowAnswerFieldContext> fieldsByPath) {

        WorkflowAnswerFieldContext objectKeyField =
                fieldsByPath.get(
                        findFieldPath(
                                fieldsByPath,
                                rule.objectKeyFieldId()
                        )
                );

        String objectKeyPath =
                objectKeyField == null
                        ? null
                        : objectKeyField.fieldPath();

        SimpleJsonPathReader.ReadResult objectKeyResult =
                readRelative(
                        object,
                        rule.objectPath(),
                        objectKeyPath
                );

        String objectId =
                objectKeyResult.found()
                        ? scalarText(objectKeyResult.value())
                        : null;

        List<ConditionResult> conditionResults =
                new ArrayList<>();

        for (WorkflowRiskConditionSpec condition :
                rule.conditions()) {

            conditionResults.add(
                    evaluateCondition(
                            rule,
                            condition,
                            object,
                            fieldsByPath
                    )
            );
        }

        Status status = combine(
                rule,
                conditionResults
        );

        String reason = resolveReason(
                status,
                conditionResults
        );

        if (!StringUtils.hasText(objectId)) {
            status = Status.UNKNOWN;
            reason = "业务对象标识缺失，无法形成可靠风险结论";
        }

        List<Evidence> evidence = conditionResults
                .stream()
                .flatMap(result -> result.evidence().stream())
                .toList();

        return new WorkflowRiskEvaluation(
                rule.code(),
                rule.name(),
                rule.severity(),
                objectId,
                status,
                evidence,
                reason
        );
    }

    private ConditionResult evaluateCondition(
            WorkflowRiskRuleSpec rule,
            WorkflowRiskConditionSpec condition,
            JsonNode object,
            Map<String, WorkflowAnswerFieldContext> fieldsByPath) {

        WorkflowAnswerFieldContext leftContext =
                findFieldContext(
                        fieldsByPath,
                        condition.leftFieldId()
                );

        SimpleJsonPathReader.ReadResult leftResult =
                readRelative(
                        object,
                        rule.objectPath(),
                        condition.leftPath()
                );

        Evidence leftEvidence = buildEvidence(
                condition.leftFieldId(),
                leftContext,
                leftResult
        );

        if (!leftResult.found()) {
            return unknown(
                    leftEvidence,
                    "条件字段路径不存在"
            );
        }

        if (condition.operator() == RiskOperator.IS_NULL
                || condition.operator() == RiskOperator.NOT_NULL) {

            boolean empty = isEmptyValue(
                    leftResult.value()
            );

            boolean matched =
                    condition.operator()
                            == RiskOperator.IS_NULL
                            ? empty
                            : !empty;

            return result(
                    matched,
                    List.of(leftEvidence)
            );
        }

        if (isEmptyValue(leftResult.value())) {
            return unknown(
                    leftEvidence,
                    "条件字段没有有效值"
            );
        }

        if (condition.rightType() == RiskRightType.FIELD) {
            return evaluateFieldComparison(
                    rule,
                    condition,
                    object,
                    leftResult.value(),
                    leftEvidence,
                    leftContext,
                    fieldsByPath
            );
        }

        Boolean matched = compare(
                leftResult.value(),
                condition.constantValue(),
                condition.operator(),
                isNumberField(leftContext)
        );

        if (matched == null) {
            return unknown(
                    leftEvidence,
                    "条件值无法转换为可比较类型"
            );
        }

        return result(
                matched,
                List.of(leftEvidence)
        );
    }

    private ConditionResult evaluateFieldComparison(
            WorkflowRiskRuleSpec rule,
            WorkflowRiskConditionSpec condition,
            JsonNode object,
            JsonNode leftValue,
            Evidence leftEvidence,
            WorkflowAnswerFieldContext leftContext,
            Map<String, WorkflowAnswerFieldContext> fieldsByPath) {

        WorkflowAnswerFieldContext rightContext =
                findFieldContext(
                        fieldsByPath,
                        condition.rightFieldId()
                );

        SimpleJsonPathReader.ReadResult rightResult =
                readRelative(
                        object,
                        rule.objectPath(),
                        condition.rightPath()
                );

        Evidence rightEvidence = buildEvidence(
                condition.rightFieldId(),
                rightContext,
                rightResult
        );

        if (!rightResult.found()
                || isEmptyValue(rightResult.value())) {

            return unknown(
                    List.of(leftEvidence, rightEvidence),
                    "右侧比较字段不存在或没有有效值"
            );
        }

        String rightValue;

        if (isNumberField(leftContext)) {
            BigDecimal decimal =
                    toDecimal(rightResult.value());

            if (decimal == null) {
                return unknown(
                        List.of(leftEvidence, rightEvidence),
                        "右侧字段无法转换为数字"
                );
            }

            rightValue = decimal
                    .multiply(condition.multiplier())
                    .toPlainString();
        } else {
            rightValue = scalarText(
                    rightResult.value()
            );
        }

        Boolean matched = compare(
                leftValue,
                rightValue,
                condition.operator(),
                isNumberField(leftContext)
        );

        if (matched == null) {
            return unknown(
                    List.of(leftEvidence, rightEvidence),
                    "左右字段无法进行可靠比较"
            );
        }

        return result(
                matched,
                List.of(leftEvidence, rightEvidence)
        );
    }

    /**
     * 返回null表示本次比较无法形成可靠结论。
     */
    private Boolean compare(
            JsonNode leftNode,
            String rightText,
            RiskOperator operator,
            boolean numeric) {

        if (leftNode == null
                || operator == null
                || !StringUtils.hasText(rightText)) {
            return null;
        }

        if (numeric) {
            BigDecimal left = toDecimal(leftNode);

            if (left == null) {
                return null;
            }

            if (operator == RiskOperator.IN
                    || operator == RiskOperator.NOT_IN) {

                boolean contained = false;

                for (String value :
                        rightText.split("[,，]")) {

                    try {
                        if (left.compareTo(
                                new BigDecimal(value.trim())
                        ) == 0) {
                            contained = true;
                            break;
                        }
                    } catch (NumberFormatException exception) {
                        return null;
                    }
                }

                return operator == RiskOperator.IN
                        ? contained
                        : !contained;
            }

            BigDecimal right;

            try {
                right = new BigDecimal(
                        rightText.trim()
                );
            } catch (NumberFormatException exception) {
                return null;
            }

            int compared = left.compareTo(right);

            return compareResult(
                    compared,
                    operator
            );
        }

        String leftText = scalarText(leftNode);

        if (!StringUtils.hasText(leftText)) {
            return null;
        }

        return switch (operator) {
            case EQ -> leftText.equals(rightText);
            case NE -> !leftText.equals(rightText);
            case CONTAINS -> leftText.contains(rightText);
            case NOT_CONTAINS -> !leftText.contains(rightText);
            case IN -> containsText(rightText, leftText);
            case NOT_IN -> !containsText(rightText, leftText);
            default -> null;
        };
    }

    private Boolean compareResult(
            int compared,
            RiskOperator operator) {

        return switch (operator) {
            case EQ -> compared == 0;
            case NE -> compared != 0;
            case GT -> compared > 0;
            case GTE -> compared >= 0;
            case LT -> compared < 0;
            case LTE -> compared <= 0;
            default -> null;
        };
    }

    private boolean containsText(
            String configuredValues,
            String target) {

        for (String value :
                configuredValues.split("[,，]")) {

            if (target.equals(value.trim())) {
                return true;
            }
        }

        return false;
    }

    private Status combine(
            WorkflowRiskRuleSpec rule,
            List<ConditionResult> results) {

        boolean hasMatched = results.stream()
                .anyMatch(result ->
                        result.status() == Status.MATCHED);

        boolean hasNotMatched = results.stream()
                .anyMatch(result ->
                        result.status() == Status.NOT_MATCHED);

        boolean hasUnknown = results.stream()
                .anyMatch(result ->
                        result.status() == Status.UNKNOWN);

        if (rule.logic()
                == WorkflowRiskRuleSpec.RiskLogic.OR) {

            if (hasMatched) {
                return Status.MATCHED;
            }

            return hasUnknown
                    ? Status.UNKNOWN
                    : Status.NOT_MATCHED;
        }

        /*
         * AND规则存在未知条件时不能输出无风险。
         */
        if (hasUnknown) {
            return Status.UNKNOWN;
        }

        return hasNotMatched
                ? Status.NOT_MATCHED
                : Status.MATCHED;
    }

    private String resolveReason(
            Status status,
            List<ConditionResult> results) {

        if (status == Status.MATCHED) {
            return "规则条件已满足";
        }

        if (status == Status.NOT_MATCHED) {
            return "规则条件未满足";
        }

        return results.stream()
                .filter(result ->
                        result.status() == Status.UNKNOWN)
                .map(ConditionResult::reason)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("存在无法确定的规则条件");
    }

    private ConditionResult result(
            boolean matched,
            List<Evidence> evidence) {

        return new ConditionResult(
                matched
                        ? Status.MATCHED
                        : Status.NOT_MATCHED,
                evidence,
                matched
                        ? "条件满足"
                        : "条件不满足"
        );
    }

    private ConditionResult unknown(
            Evidence evidence,
            String reason) {

        return unknown(
                List.of(evidence),
                reason
        );
    }

    private ConditionResult unknown(
            List<Evidence> evidence,
            String reason) {

        return new ConditionResult(
                Status.UNKNOWN,
                evidence,
                reason
        );
    }

    private Evidence buildEvidence(
            Long fieldId,
            WorkflowAnswerFieldContext context,
            SimpleJsonPathReader.ReadResult result) {

        return new Evidence(
                fieldId,
                context == null
                        ? null
                        : context.fieldName(),
                context == null
                        ? null
                        : context.label(),
                result != null && result.found()
                        ? safeDisplayValue(result.value())
                        : null
        );
    }

    private String safeDisplayValue(JsonNode value) {
        String displayValue = scalarText(value);

        if (!StringUtils.hasText(displayValue)) {
            return null;
        }

        displayValue = displayValue
                .replace("\r", " ")
                .replace("\n", " ");

        return displayValue.substring(
                0,
                Math.min(
                        displayValue.length(),
                        MAX_EVIDENCE_VALUE_LENGTH
                )
        );
    }

    private BigDecimal toDecimal(JsonNode value) {
        if (value == null || value.isContainerNode()) {
            return null;
        }

        try {
            return value.isNumber()
                    ? value.decimalValue()
                    : new BigDecimal(
                    value.asText().trim()
            );
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String scalarText(JsonNode value) {
        if (value == null
                || value.isNull()
                || value.isContainerNode()) {
            return null;
        }

        String text = value.asText("").trim();

        return StringUtils.hasText(text)
                ? text
                : null;
    }

    private boolean isEmptyValue(JsonNode value) {
        return value == null
                || value.isNull()
                || value.isMissingNode()
                || value.isTextual()
                && !StringUtils.hasText(
                value.asText()
        );
    }

    private boolean isNumberField(
            WorkflowAnswerFieldContext context) {

        if (context == null
                || !StringUtils.hasText(
                context.fieldType())) {
            return false;
        }

        return switch (
                context.fieldType()
                        .trim()
                        .toLowerCase()) {

            case "number",
                 "integer",
                 "int",
                 "long",
                 "float",
                 "double",
                 "decimal",
                 "bigdecimal",
                 "numeric" -> true;

            default -> false;
        };
    }

    private Map<String, WorkflowAnswerFieldContext>
    buildFieldContextMap(
            WorkflowAnswerPreparation preparation) {

        Map<String, WorkflowAnswerFieldContext> result =
                new LinkedHashMap<>();

        for (WorkflowAnswerFieldContext field :
                preparation.fieldPolicy().visibleFields()) {

            if (field != null
                    && StringUtils.hasText(
                    field.fieldPath())) {

                result.putIfAbsent(
                        field.fieldPath().trim(),
                        field
                );
            }
        }

        return result;
    }

    private String findFieldPath(Map<String, WorkflowAnswerFieldContext> fields, Long fieldId) {
        WorkflowAnswerFieldContext context = findFieldContext(fields, fieldId);
        return context == null ? null : context.fieldPath();
    }

    private WorkflowAnswerFieldContext findFieldContext(Map<String, WorkflowAnswerFieldContext> fields, Long fieldId) {
        if (fieldId == null) {
            return null;
        }
        return fields.values()
                .stream()
                .filter(context ->
                        fieldId.equals(context.fieldId()))
                .findFirst()
                .orElse(null);
    }

    private SimpleJsonPathReader.ReadResult readRelative(
            JsonNode object,
            String objectPath,
            String fieldPath) {

        if (!StringUtils.hasText(objectPath)
                || !StringUtils.hasText(fieldPath)
                || !fieldPath.startsWith(objectPath)) {

            return SimpleJsonPathReader.ReadResult.missing();
        }

        String suffix = fieldPath.substring(
                objectPath.length()
        );

        String relativePath =
                StringUtils.hasText(suffix)
                        ? "$" + suffix
                        : "$";

        return jsonPathReader.read(
                object,
                relativePath.replace("[]", "[*]")
        );
    }

    private List<JsonNode> readRuleObjects(
            List<JsonNode> roots,
            String objectPath) {

        if (!StringUtils.hasText(objectPath)) {
            return List.of();
        }

        String normalizedPath =
                objectPath.replace("[]", "[*]");

        for (JsonNode root : roots) {
            SimpleJsonPathReader.ReadResult result =
                    jsonPathReader.read(
                            root,
                            normalizedPath
                    );

            if (!result.found()) {
                continue;
            }

            if (result.value().isArray()) {
                List<JsonNode> values =
                        new ArrayList<>();

                result.value().forEach(values::add);
                return values;
            }

            if (result.value().isObject()) {
                return List.of(result.value());
            }
        }

        return List.of();
    }

    private List<JsonNode> buildCandidateRoots(
            JsonNode root) {

        List<JsonNode> roots = new ArrayList<>();

        addCandidateRoot(roots, root);

        if (root == null || !root.isObject()) {
            return roots;
        }

        addCandidateRoot(
                roots,
                root.get("workflowData")
        );

        addCandidateRoot(
                roots,
                root.get("data")
        );

        root.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();

            addCandidateRoot(roots, value);

            if (value != null && value.isObject()) {
                addCandidateRoot(
                        roots,
                        value.get("workflowData")
                );
                addCandidateRoot(
                        roots,
                        value.get("data")
                );
            }
        });

        return roots;
    }

    private void addCandidateRoot(
            List<JsonNode> roots,
            JsonNode candidate) {

        if (candidate != null
                && !candidate.isNull()
                && !candidate.isMissingNode()) {
            roots.add(candidate);
        }
    }

    private record ConditionResult(
            Status status,
            List<Evidence> evidence,
            String reason) {

        private ConditionResult {
            evidence = evidence == null
                    ? List.of()
                    : List.copyOf(evidence);
        }
    }
}