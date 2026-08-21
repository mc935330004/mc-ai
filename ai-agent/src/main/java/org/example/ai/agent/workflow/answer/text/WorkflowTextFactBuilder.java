package org.example.ai.agent.workflow.answer.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.invocation.runtime.SimpleJsonPathReader;
import org.example.ai.agent.graph.model.risk.WorkflowRiskRuleSpec;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldContext;
import org.example.ai.agent.workflow.answer.WorkflowAnswerModelPayload;
import org.example.ai.agent.workflow.answer.WorkflowAnswerPreparation;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.workflow.answer.risk.WorkflowRiskEvaluation;
import org.example.ai.agent.workflow.answer.risk.WorkflowRiskRuleEvaluator;
import org.example.ai.agent.workflow.answer.trace.WorkflowAnswerTraceRecorder;

import java.util.*;
import java.math.BigDecimal;

/**
 * 从安全工作流结果中生成确定性事实。
 *
 * 大模型不负责统计金额，也不负责决定业务事实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTextFactBuilder {

    private static final int MAX_CORE_FACTS = 8;
    private static final int MAX_AGGREGATES = 6;

    private final ObjectMapper objectMapper;
    private final SimpleJsonPathReader jsonPathReader;
    private final WorkflowRiskRuleEvaluator riskRuleEvaluator;
    private final WorkflowAnswerTraceRecorder traceRecorder;
    /**
     * FOREACH 单项执行结果中的系统字段。
     * 当业务字段也叫 status 时，只排除 items[].status，
     * 不排除 items[].item.status。
     */
    private static final Set<String> FOREACH_ITEM_META_FIELDS = Set.of(
            "index",
            "status",
            "errorCode",
            "errorMessage",
            "durationMs",
            "success"
    );

    /**
     * 构建普通文字回答所需的可信事实。
     */
    public WorkflowTextFacts build(
            WorkflowAnswerPreparation preparation) {

        if (preparation == null) {
            throw new IllegalArgumentException(
                    "工作流回答准备结果不能为空"
            );
        }

        WorkflowAnswerModelPayload payload =
                preparation.modelPayload();

        JsonNode resultRoot =
                objectMapper.valueToTree(
                        payload.result()
                );

        List<WorkflowAnswerFieldContext> visibleFields =
                preparation.fieldPolicy()
                        .visibleFields();

        /*
         * 一次性展开安全结果中的全部叶子字段。
         * 支持普通结果、MERGE 和多层 FOREACH。
         */
        List<LeafValue> leaves =
                new ArrayList<>();

        collectLeafValues(
                resultRoot,
                new ArrayList<>(),
                leaves
        );

        /*
         * 只有机器字段名在字段字典中唯一时，
         * 才允许使用字段名进行路径回退。
         */
        Map<String, Integer> fieldNameCounts =
                new LinkedHashMap<>();

        for (WorkflowAnswerFieldContext field :
                visibleFields) {

            if (!StringUtils.hasText(
                    field.fieldName())) {
                continue;
            }

            fieldNameCounts.merge(
                    field.fieldName().trim(),
                    1,
                    Integer::sum
            );
        }

        Map<String, String> coreFacts =
                new LinkedHashMap<>();

        Map<String, String> aggregates =
                new LinkedHashMap<>();

        Set<String> displayObjectIds =
                new LinkedHashSet<>();

        int observedValueCount = 0;

        for (WorkflowAnswerFieldContext field :
                visibleFields) {

            if (!StringUtils.hasText(
                    field.fieldName())) {
                continue;
            }

            String fieldName =
                    field.fieldName().trim();

            List<JsonNode> values =
                    readFieldValues(
                            leaves,
                            field,
                            fieldNameCounts.getOrDefault(
                                    fieldName,
                                    0
                            )
                    );

            if (values.isEmpty()) {
                continue;
            }

            List<String> distinctValues =
                    distinctScalarValues(values);

            observedValueCount =
                    Math.max(
                            observedValueCount,
                            distinctValues.size()
                    );

            if (isProjectIdentifier(field)) {
                displayObjectIds.addAll(
                        distinctValues
                );
            }

            String label =
                    resolveLabel(field);

            /*
             * 金额等聚合字段只有在结果结构路径唯一时
             * 才会进入这里，禁止重复累加列表和详情金额。
             */
            if (field.aggregatable()
                    && aggregates.size()
                    < MAX_AGGREGATES) {

                BigDecimal sum =
                        sumNumericValues(values);

                if (sum != null) {
                    aggregates.putIfAbsent(
                            label,
                            formatNumber(sum)
                    );
                }

                continue;
            }

            /*
             * 单项目或所有记录值一致时展示核心事实。
             * 多项目不同项目名称不会全部堆到页面上。
             */
            if (distinctValues.size() == 1
                    && coreFacts.size()
                    < MAX_CORE_FACTS) {

                coreFacts.putIfAbsent(
                        label,
                        distinctValues.get(0)
                );
            }
        }

        int recordCount =
                resolveRecordCount(
                        payload,
                        observedValueCount
                );

        boolean dataComplete =
                resolveDataComplete(payload);

        RiskSummary riskSummary =
                evaluateRisk(preparation);

        String markdown =
                renderMarkdown(
                        preparation,
                        recordCount,
                        dataComplete,
                        coreFacts,
                        aggregates,
                        riskSummary
                );

        Map<String, Object> safeModelInput =
                new LinkedHashMap<>();

        safeModelInput.put(
                "queryStatus",
                "SUCCESS"
        );

        if (recordCount > 0) {
            safeModelInput.put(
                    "recordCount",
                    recordCount
            );
        }

        safeModelInput.put(
                "dataComplete",
                dataComplete
        );

        safeModelInput.put(
                "coreFacts",
                coreFacts
        );

        safeModelInput.put(
                "aggregates",
                aggregates
        );

        /*
         * 没有配置风险规则时不把空风险信息发送给模型，
         * 避免模型把注意力放在用户没有询问的内容上。
         */
        if (riskSummary.configured()
                || riskSummary.failed()) {

            safeModelInput.put(
                    "riskEvaluation",
                    riskSummary.toSafeModelInput()
            );
        }

        return new WorkflowTextFacts(
                markdown,
                List.copyOf(displayObjectIds),
                riskSummary.riskObjectIds(),
                riskSummary.unknownObjectIds(),
                safeModelInput,
                dataComplete
        );
    }



    /**
     * 从完整安全结果中读取字段值。
     *
     * 优先按照完整字段路径后缀匹配；
     * 工作流改变外层结构后，仅在机器字段名唯一时回退。
     */
    private List<JsonNode> readFieldValues(
            List<LeafValue> leaves,
            WorkflowAnswerFieldContext field,
            int fieldNameCount) {

        List<String> targetTokens =
                parseFieldPath(
                        field.fieldPath()
                );

        List<LeafValue> matches =
                targetTokens.isEmpty()
                        ? List.of()
                        : leaves.stream()
                        .filter(value ->
                                endsWith(
                                        value.pathTokens(),
                                        targetTokens
                                )
                        )
                        .toList();

        /*
         * FOREACH 会改变字段的外层包装。
         * 只有字段名在当前工作流字段字典中唯一时，
         * 才允许使用机器字段名回退。
         */
        if (matches.isEmpty()
                && fieldNameCount == 1
                && StringUtils.hasText(
                field.fieldName())) {

            String fieldName =
                    field.fieldName().trim();

            matches =
                    leaves.stream()
                            .filter(value ->
                                    !value.pathTokens()
                                            .isEmpty()
                            )
                            .filter(value ->
                                    fieldName.equals(
                                            value.pathTokens()
                                                    .get(
                                                            value.pathTokens()
                                                                    .size() - 1
                                                    )
                                    )
                            )
                            .filter(value ->
                                    !isForEachMetaField(
                                            value
                                    )
                            )
                            .toList();
        }

        if (matches.isEmpty()) {
            return List.of();
        }

        Map<String, List<JsonNode>> valuesByPath =
                new LinkedHashMap<>();

        for (LeafValue match : matches) {

            String pathKey =
                    String.join(
                            ".",
                            match.pathTokens()
                    );

            valuesByPath
                    .computeIfAbsent(
                            pathKey,
                            ignored ->
                                    new ArrayList<>()
                    )
                    .add(
                            match.value()
                    );
        }

        /*
         * 同一个金额字段出现在列表和详情两个区域时，
         * 不能盲目累加，否则金额会翻倍。
         */
        if (field.aggregatable()
                && valuesByPath.size() != 1) {

            log.warn(
                    "工作流文字回答跳过多路径聚合字段，"
                            + "fieldName={}，pathCount={}",
                    field.fieldName(),
                    valuesByPath.size()
            );

            return List.of();
        }

        List<JsonNode> result =
                new ArrayList<>();

        valuesByPath.values()
                .forEach(result::addAll);

        return List.copyOf(result);
    }

    /**
     * 递归收集最终结果中的叶子字段。
     *
     * 数组下标统一表示为 []，
     * 避免同一个字段因为数组下标不同被识别成不同路径。
     */
    private void collectLeafValues(
            JsonNode node,
            List<String> path,
            List<LeafValue> result) {

        if (node == null
                || node.isMissingNode()) {
            return;
        }

        if (node.isObject()) {

            node.fields()
                    .forEachRemaining(field -> {

                        /*
                         * displayData 是中文展示副本，
                         * 文字回答只使用机器字段数据，
                         * 避免同一业务值被重复采集。
                         */
                        if ("displayData".equals(
                                field.getKey())) {
                            return;
                        }

                        List<String> childPath =
                                new ArrayList<>(path);

                        childPath.add(
                                field.getKey()
                        );

                        collectLeafValues(
                                field.getValue(),
                                childPath,
                                result
                        );
                    });

            return;
        }

        if (node.isArray()) {

            for (JsonNode child : node) {

                List<String> childPath =
                        new ArrayList<>(path);

                childPath.add("[]");

                collectLeafValues(
                        child,
                        childPath,
                        result
                );
            }

            return;
        }

        result.add(
                new LeafValue(
                        List.copyOf(path),
                        node
                )
        );
    }

    /**
     * 将字段字典路径转换为可比较的路径片段。
     */
    private List<String> parseFieldPath(
            String fieldPath) {

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

            if (segment.endsWith("[]")) {

                String name =
                        segment.substring(
                                0,
                                segment.length() - 2
                        );

                if (StringUtils.hasText(name)) {
                    result.add(name);
                }

                result.add("[]");
            } else {
                result.add(segment);
            }
        }

        return List.copyOf(result);
    }

    /**
     * 判断实际结果路径是否以字段字典路径结尾。
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
                    actual.get(
                            offset + index
                    ),
                    expected.get(index))) {
                return false;
            }
        }

        return true;
    }

    /**
     * 排除 FOREACH 单项包装中的系统字段。
     *
     * 例如排除 items[].status，
     * 但保留 items[].item.status。
     */
    private boolean isForEachMetaField(
            LeafValue value) {

        List<String> path =
                value.pathTokens();

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

        if (!itemEnvelope) {
            return false;
        }

        return FOREACH_ITEM_META_FIELDS.contains(
                path.get(size - 1)
        );
    }

    /**
     * 最终结果中的叶子字段及其结构路径。
     */
    private record LeafValue(
            List<String> pathTokens,
            JsonNode value) {
    }

    private List<String> distinctScalarValues(
            List<JsonNode> values) {

        Set<String> distinct =
                new LinkedHashSet<>();

        for (JsonNode value : values) {

            String text = formatScalar(value);

            if (StringUtils.hasText(text)) {
                distinct.add(text);
            }
        }

        return List.copyOf(distinct);
    }

    private String formatScalar(JsonNode value) {

        if (value == null
                || value.isNull()
                || value.isContainerNode()) {
            return null;
        }

        if (value.isNumber()) {
            return formatNumber(value.decimalValue());
        }

        String text = value.asText("").trim();

        if (!StringUtils.hasText(text)) {
            return null;
        }

        /*
         * 防止业务文本破坏 Markdown 展示。
         */
        text = text
                .replace("\r", " ")
                .replace("\n", " ")
                .replace("*", "\\*")
                .replace("|", "\\|");

        return text.substring(
                0,
                Math.min(text.length(), 160)
        );
    }

    private BigDecimal sumNumericValues(
            List<JsonNode> values) {

        BigDecimal sum = BigDecimal.ZERO;
        boolean found = false;

        for (JsonNode value : values) {
            if (value == null || !value.isNumber()) {
                continue;
            }

            sum = sum.add(value.decimalValue());
            found = true;
        }

        return found ? sum : null;
    }

    private String formatNumber(
            BigDecimal value) {

        return value.stripTrailingZeros()
                .toPlainString();
    }

    private int resolveRecordCount(
            WorkflowAnswerModelPayload payload,
            int observedValueCount) {

        int batchCount =
                payload.batches()
                        .stream()
                        .mapToInt(
                                WorkflowAnswerModelPayload.Batch
                                        ::totalCount
                        )
                        .max()
                        .orElse(0);

        return Math.max(
                batchCount,
                observedValueCount
        );
    }

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

    private String resolveLabel(
            WorkflowAnswerFieldContext field) {

        if (StringUtils.hasText(field.label())) {
            return field.label().trim();
        }

        return field.fieldName();
    }

    /**
     * 生成面向用户的业务回答。
     *
     * 工作流状态、完整性成功等技术信息不作为回答主体。
     */
    private String renderMarkdown(
            WorkflowAnswerPreparation preparation,
            int recordCount,
            boolean dataComplete,
            Map<String, String> coreFacts,
            Map<String, String> aggregates,
            RiskSummary riskSummary) {

        if (recordCount <= 0) {
            return "没有查询到符合条件的项目数据。";
        }

        StringBuilder markdown =
                new StringBuilder();

        markdown.append("查询到 **")
                .append(recordCount)
                .append(" 条符合条件的项目数据**。");

        if (!dataComplete) {
            markdown.append(
                    "\n\n> 部分业务数据未完整返回，"
                            + "以下内容仅基于当前成功返回的数据。"
            );
        }

        if (!coreFacts.isEmpty()) {

            markdown.append(
                    "\n\n**项目关键信息**\n\n"
            );

            coreFacts.forEach(
                    (label, value) ->
                            markdown.append("- **")
                                    .append(label)
                                    .append("：** ")
                                    .append(value)
                                    .append("\n")
            );
        }

        if (!aggregates.isEmpty()) {

            markdown.append(
                    "\n**数据汇总**\n\n"
            );

            aggregates.forEach(
                    (label, value) ->
                            markdown.append("- **")
                                    .append(label)
                                    .append("合计：** ")
                                    .append(value)
                                    .append("\n")
            );
        }

        /*
         * 查询到记录却没有任何业务字段时记录后台告警。
         * 用户侧不再显示“字段解析失败”等系统术语。
         */
        if (coreFacts.isEmpty()
                && aggregates.isEmpty()) {

            log.warn(
                    "工作流文字回答未提取到可展示字段，"
                            + "runId={}，workflowCode={}",
                    preparation.outcome().runId(),
                    preparation.outcome()
                            .workflowCode()
            );

            markdown.append(
                    "\n\n当前结果暂时没有可展示的项目明细。"
            );
        }

        appendRiskSummary(
                markdown,
                riskSummary
        );

        return markdown.toString().trim();
    }

    /**
     * 只有配置了风险规则或规则执行失败时，
     * 才向用户展示风险信息。
     */
    private void appendRiskSummary(
            StringBuilder markdown,
            RiskSummary riskSummary) {

        if (riskSummary.failed()) {

            markdown.append(
                    "\n\n**风险提示**\n\n"
                            + "- 风险规则本次执行失败，"
                            + "当前不能据此认定项目没有风险。\n"
            );

            return;
        }

        if (!riskSummary.configured()) {
            return;
        }

        markdown.append(
                "\n\n**风险判定**\n\n"
        );

        markdown.append("- **风险项目：** ")
                .append(
                        riskSummary.riskObjectIds()
                                .size()
                )
                .append(" 个\n");

        markdown.append("- **状态未知：** ")
                .append(
                        riskSummary.unknownCount()
                )
                .append(" 个\n");

        markdown.append("- **未命中风险规则：** ")
                .append(
                        riskSummary.normalObjectCount()
                )
                .append(" 个\n");

        if (!riskSummary.riskObjectIds()
                .isEmpty()) {

            markdown.append("- **风险项目编码：** ")
                    .append(
                            String.join(
                                    "、",
                                    riskSummary.riskObjectIds()
                                            .stream()
                                            .limit(10)
                                            .toList()
                            )
                    )
                    .append("\n");
        }

        if (!riskSummary.unknownObjectIds()
                .isEmpty()) {

            markdown.append("- **需要补充数据的项目：** ")
                    .append(
                            String.join(
                                    "、",
                                    riskSummary.unknownObjectIds()
                                            .stream()
                                            .limit(10)
                                            .toList()
                            )
                    )
                    .append("\n");
        }
    }

    private RiskSummary evaluateRisk(WorkflowAnswerPreparation preparation) {
        long startedAt = System.currentTimeMillis();
        try {
            List<WorkflowRiskEvaluation> evaluations = riskRuleEvaluator.evaluate(preparation);

            /*
             * 空集合表示当前工作流没有启用风险规则，
             * 不需要生成无意义的审计步骤。
             */
            if (evaluations.isEmpty()) {
                return RiskSummary.notConfigured();
            }
            traceRecorder.recordRiskEvaluation(preparation.outcome().runId(), preparation.outcome().versionId(), evaluations, System.currentTimeMillis() - startedAt);
            return summarizeRisk(evaluations);
        } catch (RuntimeException exception) {
            log.warn(
                    "工作流风险规则判定失败，runId={}，workflowCode={}，errorType={}",
                    preparation.outcome().runId(),
                    preparation.outcome().workflowCode(),
                    exception.getClass().getSimpleName(),
                    exception
            );
            traceRecorder.recordRiskEvaluationFailure(preparation.outcome().runId(),
                    preparation.outcome().versionId(), System.currentTimeMillis() - startedAt);
            /*
             * 判定失败只能标记未知，
             * 不能错误告诉用户当前项目没有风险。
             */
            return RiskSummary.evaluationFailed();
        }
    }

    private RiskSummary summarizeRisk(List<WorkflowRiskEvaluation> evaluations) {
        List<WorkflowRiskEvaluation> sorted = evaluations.stream()
                .filter(Objects::nonNull)
                // 显式指定比较器元素类型，避免泛型被推断为 Object。
                .sorted(Comparator.comparingInt((WorkflowRiskEvaluation item) -> statusPriority(item.status()))
                        .thenComparingInt(item -> severityPriority(item.severity()))).toList();

        LinkedHashSet<String> allObjectIds = new LinkedHashSet<>();
        LinkedHashSet<String> riskObjectIds = new LinkedHashSet<>();
        LinkedHashSet<String> unknownObjectIds = new LinkedHashSet<>();
        boolean unknownWithoutObjectId = false;
        for (WorkflowRiskEvaluation evaluation : sorted) {
            String objectId = normalizeObjectId(evaluation.objectId());

            if (objectId != null) {
                allObjectIds.add(objectId);
            }

            if (evaluation.status() == WorkflowRiskEvaluation.Status.MATCHED && objectId != null) {
                riskObjectIds.add(objectId);
                unknownObjectIds.remove(objectId);
                continue;
            }
            if (evaluation.status() == WorkflowRiskEvaluation.Status.UNKNOWN) {
                if (objectId == null) {
                    unknownWithoutObjectId = true;
                } else if (!riskObjectIds.contains(objectId)) {
                    unknownObjectIds.add(objectId);
                }
            }
        }

        int normalObjectCount = Math.max(0, allObjectIds.size() - riskObjectIds.size() - unknownObjectIds.size());

        int unknownCount = unknownObjectIds.size() + (unknownWithoutObjectId ? 1 : 0);

        List<Map<String, Object>> findings =
                sorted.stream()
                        .filter(item ->
                                item.status()
                                        != WorkflowRiskEvaluation.Status.NOT_MATCHED)
                        .limit(20)
                        .map(this::toSafeFinding)
                        .toList();

        return new RiskSummary(true, false, List.copyOf(riskObjectIds),
                List.copyOf(unknownObjectIds), unknownCount, normalObjectCount, findings);
    }

    private Map<String, Object> toSafeFinding(WorkflowRiskEvaluation evaluation) {

        Map<String, Object> finding = new LinkedHashMap<>();

        finding.put("objectId", normalizeObjectId(evaluation.objectId()));
        finding.put("ruleName", evaluation.ruleName());
        finding.put("severity", evaluation.severity() == null
                        ? null
                        : evaluation.severity().name());
        finding.put("status", evaluation.status() == null
                        ? "UNKNOWN"
                        : evaluation.status().name());
        finding.put("reason", evaluation.reason());

        List<Map<String, String>> evidence = evaluation.evidence()
                        .stream()
                        .limit(6)
                        .map(item -> {
                            Map<String, String> value = new LinkedHashMap<>();
                            value.put("label", item.label());
                            value.put("value", item.displayValue());
                            return value;
                        })
                        .toList();
        finding.put("evidence", evidence);
        return finding;
    }

    private int statusPriority(WorkflowRiskEvaluation.Status status) {
        if (status == WorkflowRiskEvaluation.Status.MATCHED) {
            return 0;
        }
        if (status == WorkflowRiskEvaluation.Status.UNKNOWN) {
            return 1;
        }
        return 2;
    }

    private int severityPriority(WorkflowRiskRuleSpec.RiskSeverity severity) {
        if (severity == WorkflowRiskRuleSpec.RiskSeverity.HIGH) {
            return 0;
        }
        if (severity == WorkflowRiskRuleSpec.RiskSeverity.MEDIUM) {
            return 1;
        }
        return 2;
    }

    private String normalizeObjectId(String objectId) {
        return StringUtils.hasText(objectId)
                ? objectId.trim()
                : null;
    }

    private record RiskSummary(
            boolean configured,
            boolean failed,
            List<String> riskObjectIds,
            List<String> unknownObjectIds,
            int unknownCount,
            int normalObjectCount,
            List<Map<String, Object>> findings) {

        private RiskSummary {
            riskObjectIds = riskObjectIds == null
                    ? List.of()
                    : List.copyOf(riskObjectIds);

            unknownObjectIds = unknownObjectIds == null
                    ? List.of()
                    : List.copyOf(unknownObjectIds);

            findings = findings == null
                    ? List.of()
                    : List.copyOf(findings);
        }

        private static RiskSummary notConfigured() {
            return new RiskSummary(false, false, List.of(),
                    List.of(), 0, 0, List.of());
        }

        private static RiskSummary evaluationFailed() {
            return new RiskSummary(false, true,
                    List.of(), List.of(), 1, 0, List.of()
            );
        }

        private Map<String, Object> toSafeModelInput() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("configured", configured);
            result.put("failed", failed);
            result.put("riskObjectIds", riskObjectIds);
            result.put("unknownObjectIds", unknownObjectIds);
            result.put("unknownCount", unknownCount);
            result.put("normalObjectCount", normalObjectCount);
            result.put("findings", findings);
            return result;
        }
    }
}
