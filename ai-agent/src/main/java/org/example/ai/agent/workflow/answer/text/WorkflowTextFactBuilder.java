package org.example.ai.agent.workflow.answer.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.answer.extractor.DictionaryFactExtractor;
import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.answer.model.FactValueCandidate;
import org.example.ai.agent.answer.model.UnifiedFactSet;
import org.example.ai.agent.answer.text.BusinessTextFacts;
import org.example.ai.agent.answer.text.SafeModelInputBuilder;
import org.example.ai.agent.common.enums.FactSourceType;
import org.example.ai.agent.tool.FieldMeta;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldContext;
import org.example.ai.agent.workflow.answer.WorkflowAnswerModelPayload;
import org.example.ai.agent.workflow.answer.WorkflowAnswerPreparation;
import org.example.ai.agent.workflow.answer.calculation.WorkflowCalculationFactService;
import org.example.ai.agent.workflow.answer.risk.WorkflowRiskEvaluation;
import org.example.ai.agent.workflow.answer.risk.WorkflowRiskRuleEvaluator;
import org.example.ai.agent.workflow.answer.trace.WorkflowAnswerTraceRecorder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 将工作流嵌套结果转换为统一事实集合。
 *
 * 本类只负责：
 * 1. 定位字段字典对应的数据；
 * 2. 生成统一事实；
 * 3. 执行风险规则并生成规则事实；
 * 4. 准备安全模型输入。
 *
 * 不负责生成 Markdown、HTML、统计结论或页面布局。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTextFactBuilder {

    private static final Set<String> FOREACH_META_FIELDS =
            Set.of(
                    "index",
                    "status",
                    "errorCode",
                    "errorMessage",
                    "durationMs",
                    "success"
            );
    private final WorkflowCalculationFactService calculationFactService;
    private final ObjectMapper objectMapper;
    private final DictionaryFactExtractor factExtractor;
    private final WorkflowRiskRuleEvaluator riskRuleEvaluator;
    private final WorkflowAnswerTraceRecorder traceRecorder;
    private final SafeModelInputBuilder safeModelInputBuilder;

    /**
     * 构建工作流统一事实上下文。
     */
    public BusinessTextFacts build(WorkflowAnswerPreparation preparation) {
        if (preparation == null || preparation.internalPayload() == null) {
            throw new IllegalArgumentException("工作流回答准备结果不能为空");
        }

        WorkflowAnswerModelPayload payload = preparation.internalPayload();
        List<WorkflowAnswerFieldContext> fields =
                preparation.fieldPolicy() == null
                        ? List.of()
                        : preparation.fieldPolicy().internalFields();

        Set<String> displayObjectIds = new LinkedHashSet<>();
        UnifiedFactSet extracted = extractBusinessFields(
                objectMapper.valueToTree(payload.result()),
                fields,
                displayObjectIds
        );

        // 普通查询继续执行原有公式计算和风险规则。
        List<AnswerFact> calculatedFacts =
                calculationFactService.calculate(preparation);
        RiskResult riskResult = evaluateRisk(preparation);

        List<AnswerFact> allFacts = new ArrayList<>(extracted.facts());
        allFacts.addAll(calculatedFacts);
        allFacts.addAll(riskResult.facts());

        long totalCount = resolveRecordCount(payload, extracted);
        boolean calculationComplete =
                calculatedFacts.stream().noneMatch(AnswerFact::isMissing);
        boolean dataComplete = resolveDataComplete(payload)
                && extracted.dataComplete()
                && calculationComplete;

        UnifiedFactSet factSet =
                new UnifiedFactSet(allFacts, dataComplete, totalCount);
        Map<String, Object> safeModelInput =
                buildSafeModelInput(factSet, riskResult);

        return new BusinessTextFacts(
                factSet,
                List.copyOf(displayObjectIds),
                riskResult.riskObjectIds(),
                riskResult.unknownObjectIds(),
                safeModelInput
        );
    }

    /**
     * 从已校验的快照恢复业务事实。
     * 不重新查询业务系统，也不重新执行公式和风险规则。
     */
    public UnifiedFactSet buildSnapshot(
            WorkflowAnswerModelPayload payload,
            List<WorkflowAnswerFieldContext> fields,
            boolean sourceComplete) {

        if (payload == null) {
            throw new IllegalArgumentException("快照业务数据不能为空");
        }

        UnifiedFactSet extracted = extractBusinessFields(
                objectMapper.valueToTree(payload.result()),
                fields == null ? List.of() : fields,
                new LinkedHashSet<>()
        );

        boolean dataComplete = sourceComplete
                && resolveDataComplete(payload)
                && extracted.dataComplete();

        return new UnifiedFactSet(
                extracted.facts(),
                dataComplete,
                resolveRecordCount(payload, extracted)
        );
    }

    /**
     * 提取真实业务字段，并为已有记录补充必答字段的缺失标记。
     */
    private UnifiedFactSet extractBusinessFields(JsonNode resultRoot, List<WorkflowAnswerFieldContext> fields, Set<String> displayObjectIds) {
        List<LeafValue> leaves = new ArrayList<>();
        List<LeafValue> objects = new ArrayList<>();
        collectLeaves(resultRoot, List.of(), "$", null, leaves, objects);

        Map<String, Integer> fieldNameCounts = countFieldNames(fields);
        List<FactValueCandidate> candidates = new ArrayList<>();

        Set<String> recordPaths = new LinkedHashSet<>();
        boolean scalarRecord = false;
        for (WorkflowAnswerFieldContext field : fields) {
            if (field == null || !StringUtils.hasText(field.fieldName())) {
                continue;
            }
            FieldMeta fieldMeta = toFieldMeta(field);
            List<String> expectedPath = parseFieldPath(field.fieldPath());
            List<LeafValue> matches;
            if (field.requiredOutput()) {
                // 从真实父对象读取，可以识别“这条记录缺少该字段”。
                matches = findRequiredValues(objects, field);

                if (matches.isEmpty()) {
                    // 必答字段只允许完整路径匹配，不猜测其它集合的同名字段。
                    matches = leaves.stream()
                            .filter(leaf ->
                                    endsWith(leaf.pathTokens(), expectedPath))
                            .toList();
                }
            } else {
                matches = findMatches(leaves, field, fieldNameCounts);
            }

            if (matches.isEmpty()) {
                // 非集合字段保留缺失事实，但不能把它计算为一条真实记录。
                if (field.requiredOutput() && !expectedPath.contains("[]")) {
                    candidates.add(new FactValueCandidate(
                            field.capabilityCode(),
                            fieldMeta,
                            null,
                            null,
                            null,
                            true,
                            "PATH_NOT_FOUND",
                            FactSourceType.RAW
                    ));
                }
                continue;
            }

            for (LeafValue match : matches) {
                JsonNode value = match.value();
                String missingReason = null;

                if (value == null || value.isMissingNode()) {
                    missingReason = "PATH_NOT_FOUND";
                } else if (value.isNull()) {
                    missingReason = "VALUE_NULL";
                } else if (value.isContainerNode()) {
                    // 字段只接受标量，不能把整个对象或数组作为展示值。
                    missingReason = "VALUE_NOT_SCALAR";
                }

                boolean missing = missingReason != null;

                if (missing && !field.requiredOutput()) {
                    continue;
                }

                candidates.add(new FactValueCandidate(
                        field.capabilityCode(),
                        fieldMeta,
                        value,
                        match.recordPath(),
                        normalizeCollectionPath(match.recordPath()),
                        missing,
                        missingReason,
                        FactSourceType.RAW
                ));

                // 只统计绑定到真实对象的记录，缺失字段本身不制造记录。
                if (StringUtils.hasText(match.recordPath())) {
                    recordPaths.add(match.recordPath());
                } else {
                    scalarRecord = true;
                }

                if (!missing && isProjectIdentifier(field)) {
                    addObjectId(displayObjectIds, value);
                }
            }
        }

        UnifiedFactSet extracted = factExtractor.extractCandidates(candidates);
        long actualCount = recordPaths.isEmpty() && scalarRecord
                ? 1
                : recordPaths.size();

        return new UnifiedFactSet(
                extracted.facts(),
                extracted.dataComplete(),
                Math.max(extracted.totalCount(), actualCount)
        );
    }

    /**
     * 在已存在的父对象中读取必答字段，保留每条记录的路径。
     */
    private List<LeafValue> findRequiredValues(List<LeafValue> objects, WorkflowAnswerFieldContext field) {
        List<String> path = parseFieldPath(field.fieldPath());

        if (path.isEmpty() || "[]".equals(path.get(path.size() - 1))) {
            return List.of();
        }
        String childName = path.get(path.size() - 1);
        List<String> parentPath = path.subList(0, path.size() - 1);

        // 根字段交给原有叶子路径匹配，避免误认工作流外层包装对象。
        if (parentPath.isEmpty()) {
            return List.of();
        }
        List<LeafValue> values = new ArrayList<>();
        for (LeafValue parent : objects) {
            if (!endsWith(parent.pathTokens(), parentPath)) {
                continue;
            }
            List<String> actualTokens = new ArrayList<>(parent.pathTokens());
            actualTokens.add(childName);
            values.add(new LeafValue(
                    List.copyOf(actualTokens),
                    parent.value().path(childName),
                    parent.actualPath() + "." + childName,
                    parent.recordPath()
            ));
        }

        return values;
    }

    /**
     * 统计机器字段名在当前工作流中出现的次数。
     */
    private Map<String, Integer> countFieldNames(
            List<WorkflowAnswerFieldContext> fields) {

        Map<String, Integer> counts =
                new LinkedHashMap<>();

        for (WorkflowAnswerFieldContext field : fields) {

            if (field == null
                    || !StringUtils.hasText(
                    field.fieldName())) {

                continue;
            }

            counts.merge(
                    field.fieldName().trim(),
                    1,
                    Integer::sum
            );
        }

        return counts;
    }

    /**
     * 优先使用完整字段路径后缀匹配。
     *
     * 完整路径没有匹配结果时，
     * 只有机器字段名在工作流内唯一才允许回退。
     */
    private List<LeafValue> findMatches(
            List<LeafValue> leaves,
            WorkflowAnswerFieldContext field,
            Map<String, Integer> fieldNameCounts) {

        List<String> expectedPath =
                parseFieldPath(
                        field.fieldPath()
                );

        List<LeafValue> matches =
                leaves.stream()
                        .filter(leaf ->
                                endsWith(
                                        leaf.pathTokens(),
                                        expectedPath
                                )
                        )
                        .toList();

        if (!matches.isEmpty()) {
            return matches;
        }

        String fieldName =
                field.fieldName().trim();

        if (fieldNameCounts.getOrDefault(
                fieldName,
                0
        ) != 1) {

            return List.of();
        }

        return leaves.stream()
                .filter(leaf ->
                        lastPathNameEquals(
                                leaf,
                                fieldName
                        )
                )
                .filter(leaf ->
                        !isForEachMetaField(leaf)
                )
                .toList();
    }

    /**
     * 分别收集叶子字段和父对象，父对象仅用于定位缺失字段。
     */
    private void collectLeaves(JsonNode node, List<String> pathTokens, String currentPath,
                               String latestRecordPath, List<LeafValue> result, List<LeafValue> objects) {
        if (node == null || node.isMissingNode()) {
            return;
        }
        if (node.isObject()) {
            objects.add(new LeafValue(List.copyOf(pathTokens), node, currentPath, latestRecordPath));
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                // 展示副本不重复进入业务事实。
                if ("displayData".equals(entry.getKey())) {
                    continue;
                }
                List<String> childTokens = new ArrayList<>(pathTokens);
                childTokens.add(entry.getKey());
                collectLeaves(entry.getValue(), childTokens, currentPath + "." + entry.getKey(), latestRecordPath, result, objects);
            }
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                List<String> childTokens = new ArrayList<>(pathTokens);
                childTokens.add("[]");
                String recordPath = currentPath + "[" + index + "]";
                collectLeaves(node.get(index), childTokens, recordPath, recordPath, result, objects);
            }
            return;
        }
        result.add(new LeafValue(List.copyOf(pathTokens), node, currentPath, latestRecordPath));
    }

    /**
     * 将字段字典路径转换为统一路径片段。
     */
    private List<String> parseFieldPath(String fieldPath) {
        if (!StringUtils.hasText(fieldPath)) {
            return List.of();
        }

        String normalized =
                fieldPath.trim()
                        .replace("[*]", "[]");

        if (normalized.startsWith("$.")) {
            normalized =
                    normalized.substring(2);
        } else if (normalized.startsWith("$")) {
            normalized =
                    normalized.substring(1);
        }

        List<String> result =
                new ArrayList<>();

        for (String segment :
                normalized.split("\\.")) {

            if (!StringUtils.hasText(segment)) {
                continue;
            }

            if (!segment.endsWith("[]")) {
                result.add(segment);
                continue;
            }

            String fieldName =
                    segment.substring(
                            0,
                            segment.length() - 2
                    );

            if (StringUtils.hasText(fieldName)) {
                result.add(fieldName);
            }

            result.add("[]");
        }

        return List.copyOf(result);
    }

    /**
     * 判断实际路径是否以字段配置路径结尾。
     */
    private boolean endsWith(
            List<String> actual,
            List<String> expected) {

        if (expected.isEmpty()
                || actual.size()
                < expected.size()) {

            return false;
        }

        int offset =
                actual.size()
                        - expected.size();

        for (int index = 0;
             index < expected.size();
             index++) {

            if (!Objects.equals(
                    actual.get(offset + index),
                    expected.get(index))) {

                return false;
            }
        }

        return true;
    }

    /**
     * 判断叶子字段机器名称是否匹配。
     */
    private boolean lastPathNameEquals(
            LeafValue leaf,
            String fieldName) {

        if (leaf.pathTokens().isEmpty()) {
            return false;
        }

        String lastName =
                leaf.pathTokens().get(
                        leaf.pathTokens().size() - 1
                );

        return fieldName.equals(lastName);
    }

    /**
     * 排除 FOREACH 单项包装的运行字段。
     */
    private boolean isForEachMetaField(
            LeafValue leaf) {

        List<String> path =
                leaf.pathTokens();

        if (path.size() < 3) {
            return false;
        }

        int size =
                path.size();

        boolean itemEnvelope =
                "items".equals(
                        path.get(size - 3)
                )
                        && "[]".equals(
                        path.get(size - 2)
                );

        return itemEnvelope
                && FOREACH_META_FIELDS.contains(
                path.get(size - 1)
        );
    }

    /**
     * 将工作流字段上下文转换为统一字段元数据。
     */
    private FieldMeta toFieldMeta(WorkflowAnswerFieldContext field) {

        return FieldMeta.builder()
                .name(field.fieldName())
                .fieldCode(field.fieldCode())
                .cnName(field.label())
                .path(field.fieldPath())
                .type(field.fieldType())
                .format(field.format())
                .enumMappingJson(field.enumMappingJson())
                .nullDisplayText(field.nullDisplayText())
                .meaning(field.meaning())
                .displayGroup(field.group())
                .displayOrder(field.displayOrder())
                .requiredOutput(field.requiredOutput() ? 1 : 0)
                .modelVisible(field.modelVisible() ? 1 : 0)
                .userVisible(field.userVisible() ? 1 : 0)
                .importance(field.importance())
                .displayComponent(field.displayComponent())
                .summaryFlag(field.summaryFlag())
                .unit(field.unit())
                .precisionScale(field.precisionScale())
                .valueSource(field.valueSource())
                .build();
    }

    /**
     * 判断字段是否为项目标识。
     */
    private boolean isProjectIdentifier(
            WorkflowAnswerFieldContext field) {

        String fieldName =
                field.fieldName() == null
                        ? ""
                        : field.fieldName()
                        .toLowerCase(Locale.ROOT);

        String label =
                field.label() == null
                        ? ""
                        : field.label();

        return fieldName.contains("projectcode")
                || fieldName.contains("projectid")
                || label.contains("项目编码")
                || label.contains("项目编号");
    }

    /**
     * 收集有效项目标识。
     */
    private void addObjectId(
            Set<String> objectIds,
            JsonNode value) {

        if (value == null
                || value.isNull()
                || value.isContainerNode()) {

            return;
        }

        String objectId =
                value.asText("").trim();

        if (StringUtils.hasText(objectId)) {
            objectIds.add(objectId);
        }
    }

    /**
     * 执行风险规则并转为统一事实。
     */
    private RiskResult evaluateRisk(WorkflowAnswerPreparation preparation) {
        long startedAt = System.currentTimeMillis();
        try {
            List<WorkflowRiskEvaluation> evaluations = riskRuleEvaluator.evaluate(preparation);
            if (evaluations.isEmpty()) {
                return RiskResult.empty();
            }
            traceRecorder.recordRiskEvaluation(
                    preparation.outcome().runId(),
                    preparation.outcome().versionId(),
                    evaluations,
                    System.currentTimeMillis() - startedAt
            );
            return buildRiskResult(evaluations);

        } catch (RuntimeException exception) {

            log.warn(
                    "工作流风险规则判定失败，runId={}，workflowCode={}，errorType={}",
                    preparation.outcome().runId(),
                    preparation.outcome().workflowCode(),
                    exception.getClass().getSimpleName(), exception);
            traceRecorder.recordRiskEvaluationFailure(preparation.outcome().runId(), preparation.outcome().versionId(),
                    System.currentTimeMillis() - startedAt);
            return RiskResult.failure();
        }
    }

    /**
     * 把规则判定结果转换为事实和对象标识。
     */
    private RiskResult buildRiskResult(List<WorkflowRiskEvaluation> evaluations) {
        List<AnswerFact> facts = new ArrayList<>();
        Set<String> riskObjectIds = new LinkedHashSet<>();
        Set<String> unknownObjectIds = new LinkedHashSet<>();
        int index = 0;
        for (WorkflowRiskEvaluation evaluation : evaluations) {
            if (evaluation == null) {
                continue;
            }
            String objectId = normalizeObjectId(evaluation.objectId());
            if (evaluation.status() == WorkflowRiskEvaluation.Status.MATCHED && objectId != null) {
                riskObjectIds.add(objectId);
                unknownObjectIds.remove(objectId);
            }
            if (evaluation.status() == WorkflowRiskEvaluation.Status.UNKNOWN && objectId != null &&
                    !riskObjectIds.contains(objectId)) {
                unknownObjectIds.add(objectId);
            }
            facts.add(buildRiskFact(evaluation, index));
            index++;
        }
        return new RiskResult(facts, List.copyOf(riskObjectIds), List.copyOf(unknownObjectIds), true, false);
    }

    /**
     * 创建规则判定事实。
     */
    private AnswerFact buildRiskFact(WorkflowRiskEvaluation evaluation, int index) {
        String objectId =
                normalizeObjectId(
                        evaluation.objectId()
                );

        String ruleCode =
                StringUtils.hasText(
                        evaluation.ruleCode())
                        ? evaluation.ruleCode().trim()
                        : "unknown_rule";

        String recordPath =
                "$.riskEvaluations["
                        + index
                        + "]";

        return AnswerFact.builder()
                .factKey(
                        "workflow-risk:"
                                + ruleCode
                                + ":"
                                + (objectId == null
                                ? index
                                : objectId)
                )
                .capabilityCode("workflow-risk")
                .fieldCode(ruleCode)
                .fieldName(ruleCode)
                .fieldPath(
                        "$.riskEvaluations[]."
                                + ruleCode
                )
                .label(
                        StringUtils.hasText(
                                evaluation.ruleName())
                                ? evaluation.ruleName()
                                : ruleCode
                )
                .rawValue(
                        toRiskValue(evaluation)
                )
                .formattedValue(
                        StringUtils.hasText(
                                evaluation.reason())
                                ? evaluation.reason()
                                : statusName(
                                evaluation.status()
                        )
                )
                .valueType("object")
                .displayFormat("risk")
                .meaning("后端风险规则判定结果")
                .displayGroup("风险判定")
                .importance("HIGH")
                .displayComponent("WARNINGS")
                .summary(false)
                .displayOrder(10_000 + index)
                .requiredOutput(false)
                .modelVisible(true)
                .userVisible(true)
                .missing(false)
                .recordPath(recordPath)
                .collectionKey(
                        "workflow-risk:"
                                + "$.riskEvaluations[]"
                )
                .sourceType(
                        FactSourceType.RULE_EVALUATED
                )
                .build();
    }

    /**
     * 创建安全的风险事实值。
     */
    private Map<String, Object> toRiskValue(
            WorkflowRiskEvaluation evaluation) {

        Map<String, Object> result =
                new LinkedHashMap<>();

        result.put(
                "objectId",
                normalizeObjectId(
                        evaluation.objectId()
                )
        );

        result.put(
                "status",
                statusName(
                        evaluation.status()
                )
        );

        result.put(
                "severity",
                evaluation.severity() == null
                        ? null
                        : evaluation.severity()
                        .name()
        );

        result.put(
                "reason",
                evaluation.reason()
        );

        List<Map<String, Object>> evidence =
                new ArrayList<>();

        for (WorkflowRiskEvaluation.Evidence item :
                evaluation.evidence()) {

            Map<String, Object> evidenceItem =
                    new LinkedHashMap<>();

            evidenceItem.put(
                    "fieldName",
                    item.fieldName()
            );

            evidenceItem.put(
                    "label",
                    item.label()
            );

            evidenceItem.put(
                    "value",
                    item.displayValue()
            );

            evidence.add(evidenceItem);
        }

        result.put("evidence", evidence);

        return result;
    }

    /**
     * 创建统一模型输入，并补充工作流风险判定状态。
     */
    private Map<String, Object> buildSafeModelInput(UnifiedFactSet factSet, RiskResult riskResult) {
        Map<String, Object> result = new LinkedHashMap<>(safeModelInputBuilder.build(factSet));
        if (!riskResult.configured() && !riskResult.failed()) {
            return result;
        }
        Map<String, Object> risk = new LinkedHashMap<>();
        risk.put("configured", riskResult.configured());
        risk.put("failed", riskResult.failed());
        risk.put("riskObjectIds", riskResult.riskObjectIds());
        risk.put("unknownObjectIds", riskResult.unknownObjectIds());
        result.put("riskEvaluation", risk);
        return result;
    }

    /**
     * 读取工作流真实记录数量。
     */
    private long resolveRecordCount(
            WorkflowAnswerModelPayload payload,
            UnifiedFactSet extracted) {

        int batchCount =
                payload.batches()
                        .stream()
                        .mapToInt(
                                WorkflowAnswerModelPayload.Batch
                                        ::totalCount
                        )
                        .max()
                        .orElse(0);

        return batchCount > 0
                ? batchCount
                : extracted.totalCount();
    }

    /**
     * 判断工作流业务数据是否完整。
     */
    private boolean resolveDataComplete(
            WorkflowAnswerModelPayload payload) {

        if (!payload.success()
                || payload.partialSuccess()) {

            return false;
        }

        return payload.batches()
                .stream()
                .allMatch(batch ->
                        batch.failureCount() == 0
                                && batch.partialCount() == 0
                );
    }

    /**
     * 将记录路径中的真实下标转换为集合路径。
     */
    private String normalizeCollectionPath(
            String recordPath) {

        if (!StringUtils.hasText(recordPath)) {
            return null;
        }

        return recordPath.replaceAll(
                "\\[\\d+\\]",
                "[]"
        );
    }

    private String normalizeObjectId(
            String objectId) {

        return StringUtils.hasText(objectId)
                ? objectId.trim()
                : null;
    }

    private String statusName(
            WorkflowRiskEvaluation.Status status) {

        return status == null
                ? WorkflowRiskEvaluation.Status.UNKNOWN.name()
                : status.name();
    }

    /**
     * 工作流叶子字段定位结果。
     */
    private record LeafValue(
            List<String> pathTokens,
            JsonNode value,
            String actualPath,
            String recordPath) {
    }

    /**
     * 风险规则构建结果。
     */
    private record RiskResult(
            List<AnswerFact> facts,
            List<String> riskObjectIds,
            List<String> unknownObjectIds,
            boolean configured,
            boolean failed) {

        private RiskResult {
            facts = facts == null
                    ? List.of()
                    : List.copyOf(facts);

            riskObjectIds = riskObjectIds == null
                    ? List.of()
                    : List.copyOf(riskObjectIds);

            unknownObjectIds = unknownObjectIds == null
                    ? List.of()
                    : List.copyOf(unknownObjectIds);
        }

        private static RiskResult empty() {
            return new RiskResult(
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    false
            );
        }

        private static RiskResult failure() {
            return new RiskResult(
                    List.of(),
                    List.of(),
                    List.of(),
                    false,
                    true
            );
        }
    }
}