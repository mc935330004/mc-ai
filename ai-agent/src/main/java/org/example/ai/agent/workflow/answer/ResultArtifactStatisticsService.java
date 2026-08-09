package org.example.ai.agent.workflow.answer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldContext;
import org.example.ai.agent.workflow.answer.artifact.ResultArtifactAnalysisResult;
import org.example.ai.agent.workflow.answer.artifact.ResultArtifactSnapshot;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

/**
 * 基于上一轮完整Artifact执行确定性本地统计。
 *
 * 大模型只负责：
 * 1. 判断是否为数学统计；
 * 2. 选择统计方式；
 * 3. 从字段目录中选择字段ID。
 *
 * 大模型不接触完整业务数据，也不负责计算最终结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResultArtifactStatisticsService {

    private static final double MIN_CONFIDENCE =
            0.85D;

    private static final Set<String>
            SUPPORTED_OPERATIONS =
            Set.of(
                    "SUM",
                    "COUNT",
                    "COUNT_DISTINCT",
                    "AVG",
                    "MIN",
                    "MAX"
            );

    private final ObjectMapper objectMapper;
    private final TrackedChatClientService chatClientService;
    private final ResultArtifactDocumentAssembler assembler;

    /**
     * @return 有值表示已经完成本地统计；
     *         空值表示属于定性分析，继续走原来的模型分块链路。
     */
    public Optional<ResultArtifactAnalysisResult> tryAnalyze( AgentRequest request,
            String runId,ResultArtifactSnapshot snapshot) {

        List<WorkflowAnswerFieldContext> fields = readFieldSemantics(snapshot.fieldSemanticsJson());

        List<FieldOption> fieldOptions =buildFieldOptions(fields);

        AnalysisPlan plan;

        try {
            plan = createPlan(
                    request,
                    runId,
                    fieldOptions
            );
        } catch (Exception exception) {
            log.warn(
                    "上一轮结果统计规划失败，runId={}，conversationId={}",
                    runId,
                    request.getConversationId(),
                    exception
            );

            /*
             * 统计规划失败时不能让模型猜金额。
             * 返回友好提示，用户明确统计字段后可以重新提问。
             */
            return Optional.of(
                    guidance(
                            snapshot,
                            "暂时无法可靠识别统计字段。"
                                    + "请明确说明统计方式和字段，"
                                    + "例如：汇总“本次结算金额（含税）”的总和。"
                    )
            );
        }

        if ("QUALITATIVE".equals(plan.mode())) {
            return Optional.empty();
        }

        if (!"STATISTICS".equals(plan.mode())
                || plan.confidence() == null
                || plan.confidence()
                < MIN_CONFIDENCE) {

            return Optional.of(
                    guidance(
                            snapshot,
                            "我可以继续分析上一轮数据，"
                                    + "但还不能确定需要统计的字段或方式。"
                                    + "请明确说明“哪个字段”以及“求和、平均、"
                                    + "最大、最小或计数”。"
                    )
            );
        }

        String operation =
                normalize(plan.operation());

        if (!SUPPORTED_OPERATIONS.contains(
                operation)) {

            return Optional.of(
                    guidance(
                            snapshot,
                            "当前支持求和、计数、去重计数、"
                                    + "平均值、最大值和最小值统计。"
                    )
            );
        }

        List<FieldOption> metricFields =
                findFields(
                        fieldOptions,
                        plan.metricFieldIds()
                );

        boolean missingFieldPath = metricFields.stream()
                .anyMatch(field ->
                        !StringUtils.hasText(field.fieldPath())
                );

        if (metricFields.isEmpty() || missingFieldPath) {
            return Optional.of( guidance(snapshot,
                            "没有从上一轮报告中识别出用户指定的统计字段。"
                                    + "请明确输入字段名称和求和、平均、最大、最小或计数方式。"));
        }

        /*
         * 求和、平均值、最大值、最小值只能使用允许聚合的字段。
         * COUNT和COUNT_DISTINCT只读取字段出现次数，不强制聚合标志。
         */
        FieldOption disabledField = metricFields.stream().filter(field -> !"COUNT".equals(operation)
                                && !"COUNT_DISTINCT".equals(operation)
                                && !field.aggregatable())
                .findFirst()
                .orElse(null);
        if (disabledField != null) {
            return Optional.of(
                    guidance(
                            snapshot,
                            "字段“"
                                    + disabledField.label()
                                    + "”当前未开启聚合统计。"
                                    + "请在字段字典中将该字段设置为允许聚合后，"
                                    + "重新执行一次业务查询。"
                    )
            );
        }

        try {
            JsonNode payload =
                    assembler.assemble(
                            snapshot.chunkPlan()
                    );

            JsonNode resultNode =payload.get("result");

            if (resultNode == null || resultNode.isNull() || resultNode.isMissingNode()) {
                return Optional.of(guidance(snapshot,"上一轮查询没有返回可以统计的业务数据。"));
            }
            /*
             * 确定性统计只读取工作流机器数据。
             *
             * workflowData 是当前标准字段；
             * data 用于兼容没有 workflowData 的旧 Artifact；
             * displayData 是展示副本，禁止参与统计。
             */
            JsonNode statisticsData =resolveStatisticsData(resultNode);
            // 每个字段独立提取和计算，避免不同金额字段混在一起累计。
            List<StatisticResult> statistics = new ArrayList<>();

            for (FieldOption metricField : metricFields) {
                FieldValueSet valueSet =extractFieldValues(statisticsData, metricField );
                Calculation calculation =calculate(
                                operation,
                                valueSet,
                                metricField
                        );
                statistics.add(new StatisticResult(metricField,calculation));
            }

            return Optional.of(
                    new ResultArtifactAnalysisResult(
                            renderMarkdown(
                                    operation,
                                    statistics,
                                    Boolean.TRUE.equals(snapshot.artifact().getDataComplete()),
                                    requestsTenThousandYuan(request)),
                            resolveReportTitle(snapshot),
                            Boolean.TRUE.equals(
                                    snapshot.artifact()
                                            .getDataComplete()
                            )
                    )
            );

        } catch (AnalysisDataException exception) {
            return Optional.of(
                    guidance(
                            snapshot,
                            exception.getMessage()
                    )
            );
        }
    }

    /**
     * 模型只能从服务端生成的F1、F2等字段ID中选择，
     * 禁止直接生成JSONPath，防止字段幻觉。
     */
    private AnalysisPlan createPlan(
            AgentRequest request,
            String runId,
            List<FieldOption> fieldOptions)
            throws Exception {

        ModelCallContext context =
                ModelCallContext.builder()
                        .runId(runId)
                        .conversationId(
                                request.getConversationId()
                        )
                        .userId(request.getUserId())
                        .modelCode(request.getModelCode())
                        .callType(
                                ModelCallType
                                        .RESULT_ANALYSIS_PLANNER
                        )
                        .callSequence(1)
                        .build();

        ChatResponse response =
                chatClientService.call(
                        context,
                        buildSystemPrompt(),
                        buildUserPrompt(
                                request,
                                fieldOptions
                        ),
                        ChatOptions.builder()
                                .temperature(0.0D)
                                .topP(0.1D)
                );

        if (response == null
                || response.getResult() == null
                || response.getResult()
                .getOutput() == null
                || !StringUtils.hasText(
                response.getResult()
                        .getOutput()
                        .getText())) {

            throw new IllegalStateException(
                    "统计规划模型没有返回有效内容"
            );
        }

        String json =
                extractJson(
                        response.getResult()
                                .getOutput()
                                .getText()
                );

        AnalysisPlan original =
                objectMapper.readValue(
                        json,
                        AnalysisPlan.class
                );

        if (original == null) {
            throw new IllegalStateException(
                    "统计规划结果为空"
            );
        }
        return new AnalysisPlan(
                normalize(original.mode()),
                normalize(original.operation()),
                null,
                resolveMetricFieldIds(original),
                original.confidence(),
                trimToNull(original.reason())
        );
    }
    /**
     * 优先读取多字段协议，同时兼容旧的单字段结果。
     */
    private List<String> resolveMetricFieldIds(AnalysisPlan plan) {
        List<String> fieldIds = new ArrayList<>();
        if (plan.metricFieldIds() != null) {
            fieldIds.addAll(plan.metricFieldIds());
        }
        if (StringUtils.hasText(plan.metricFieldId())) {
            fieldIds.add(plan.metricFieldId());
        }
        return fieldIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private String buildSystemPrompt() {
        return """
                你是企业PM系统的结果统计规划器。

                你只负责判断用户对上一轮结果的分析要求，
                不读取完整业务数据，不执行计算，不回答最终结果。

                mode只允许：

                1. STATISTICS
                   用户明确要求数学统计，例如：
                   求和、总金额、多少条、去重数量、
                   平均值、最大值、最小值。

                2. QUALITATIVE
                   用户要求总结、解释、异常分析、趋势分析、
                   业务建议等非确定性分析。

                3. CLARIFY
                   用户看起来需要数学统计，
                   但是统计字段或统计方式无法确定。

                operation只允许：

                SUM
                COUNT
                COUNT_DISTINCT
                AVG
                MIN
                MAX

                必须遵守：

                1. metricFieldIds只能选择字段目录中的字段ID。
                2. 用户要求统计多个字段时，必须返回全部对应字段ID。
                3. 用户只要求一个字段时，metricFieldIds也必须返回数组。
                4. SUM、AVG、MIN、MAX必须选择aggregatable=true的字段。
                5. 不得自己编造字段ID、字段名称或者字段路径。
                6. 不得计算最终金额。
                7. 不得输出Markdown。
                8. 只输出一个JSON对象。
                9. “各自”“分别”表示需要选择用户明确提到的全部字段。
                10. “换算成万元”等单位要求不影响统计字段和统计方式选择。
                11. 单位换算由后端Java执行，规划器不得因为单位换算要求返回CLARIFY。
                
                输出格式：
                
                {
                  "mode": "STATISTICS",
                  "operation": "SUM",
                  "metricFieldIds": ["F3", "F4"],
                  "confidence": 0.98,
                  "reason": "用户要求分别汇总含税金额和不含税金额"
                }
                """;
    }

    private String buildUserPrompt(AgentRequest request,List<FieldOption> fieldOptions)throws Exception {
        return """
                用户问题：
                %s

                可选择字段目录：
                %s
                """.formatted(
                // 统计字段、统计方式和目标单位必须以用户原话为准，避免上下文改写丢失限定条件。
                request.getUserQuestion().trim(),
                objectMapper.writeValueAsString(fieldOptions)
        );
    }

    private List<WorkflowAnswerFieldContext>
    readFieldSemantics(String json) {

        if (!StringUtils.hasText(json)) {
            return List.of();
        }

        try {
            List<WorkflowAnswerFieldContext> fields =
                    objectMapper.readValue(
                            json,
                            new TypeReference<>() {
                            }
                    );

            return fields == null
                    ? List.of()
                    : List.copyOf(fields);

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "上一轮字段语义快照解析失败",
                    exception
            );
        }
    }

    private List<FieldOption> buildFieldOptions(
            List<WorkflowAnswerFieldContext> fields) {

        List<FieldOption> result =
                new ArrayList<>();

        for (int index = 0;
             index < fields.size();
             index++) {

            WorkflowAnswerFieldContext field =
                    fields.get(index);

            result.add(
                    new FieldOption(
                            "F" + (index + 1),
                            field.capabilityCode(),
                            field.fieldName(),
                            StringUtils.hasText(field.label())
                                    ? field.label()
                                    : field.fieldName(),
                            field.meaning(),
                            field.format(),
                            field.fieldPath(),
                            field.fieldType(),
                            field.aggregatable()
                    )
            );
        }

        return List.copyOf(result);
    }

    private FieldOption findField(
            List<FieldOption> fields,
            String fieldId) {

        if (!StringUtils.hasText(fieldId)) {
            return null;
        }

        return fields.stream()
                .filter(field ->
                        field.id().equalsIgnoreCase(
                                fieldId.trim()
                        )
                )
                .findFirst()
                .orElse(null);
    }

    /**
     * 根据模型返回的受控字段ID查找全部统计字段。
     *
     * 任意字段ID不存在时返回空列表，禁止部分字段静默参与计算。
     */
    private List<FieldOption> findFields(
            List<FieldOption> fields,
            List<String> fieldIds) {

        if (fieldIds == null || fieldIds.isEmpty()) {
            return List.of();
        }

        List<FieldOption> result = new ArrayList<>();

        for (String fieldId : fieldIds) {
            FieldOption field = findField(fields, fieldId);

            if (field == null) {
                return List.of();
            }

            if (!result.contains(field)) {
                result.add(field);
            }
        }

        return List.copyOf(result);
    }

    /**
     * 选择确定性统计使用的机器数据。
     *
     * 不修改 Artifact 原始内容，只选择唯一可信分支。
     */
    private JsonNode resolveStatisticsData(JsonNode resultNode) {
        JsonNode workflowData = resultNode.get("workflowData");
        if (workflowData != null && workflowData.isContainerNode()) {
            return workflowData;
        }
        /*
         * 兼容旧 Artifact。
         * 旧结构没有 workflowData 时，data 是唯一可用的业务数据。
         */
        JsonNode data = resultNode.get("data");
        if (data != null && data.isContainerNode()) {
            return data;
        }
        /*
         * 兼容结果本身就是业务对象的简单工作流。
         */
        return resultNode;
    }

    /**
     * 根据完整字段路径从最终result中提取全部值。
     *
     * 优先使用完整路径后缀匹配。
     * 如果工作流改变了外层包装，只允许在字段名对应唯一结构路径时回退。
     */
    private FieldValueSet extractFieldValues(
            JsonNode resultNode,
            FieldOption field) {

        List<LeafValue> leaves =
                new ArrayList<>();

        collectLeafValues(
                resultNode,
                new ArrayList<>(),
                leaves
        );

        List<String> targetTokens =
                parseFieldPath(
                        field.fieldPath()
                );

        List<LeafValue> matches =
                leaves.stream()
                        .filter(leaf ->
                                endsWith(
                                        leaf.pathTokens(),
                                        targetTokens
                                )
                        )
                        .toList();

        /*
         * 某些工作流会去掉接口最外层data包装。
         * 只有字段名在结果中对应唯一结构路径时才允许回退，
         * 避免同名金额字段被错误累加。
         */
        if (matches.isEmpty()) {
            matches = leaves.stream().filter(leaf ->!leaf.pathTokens().isEmpty() &&
                            field.fieldName().equals(leaf.pathTokens().get(leaf.pathTokens().size() - 1))).toList();
        }

        if (matches.isEmpty()) {
            throw new AnalysisDataException(
                    "上一轮业务数据中没有找到字段“"+ field.label() + "”。请确认字段配置已经发布后重新查询。");
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
                    .add(match.value());
        }

        /*
         * 同一个字段出现在多个不同业务结构中时不能盲目累加，
         * 否则可能把列表和详情中的重复金额计算两次。
         */
        if (valuesByPath.size() != 1) {
            throw new AnalysisDataException(
                    "字段“"+ field.label()
                            + "”在上一轮业务数据中出现于多个区域，"
                            + "为避免重复统计，本次没有计算。"
                            + "请联系管理员完善该字段的统计配置。");
        }

        Map.Entry<String, List<JsonNode>> entry =
                valuesByPath.entrySet()
                        .iterator()
                        .next();

        return new FieldValueSet(
                entry.getKey(),
                List.copyOf(entry.getValue())
        );
    }

    private void collectLeafValues(
            JsonNode node,
            List<String> path,
            List<LeafValue> result) {

        if (node == null
                || node.isMissingNode()) {
            return;
        }

        if (node.isObject()) {
            node.fields().forEachRemaining(
                    field -> {
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
                    }
            );

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

    private boolean endsWith(
            List<String> actual,
            List<String> expected) {

        if (expected.isEmpty()
                || actual.size() < expected.size()) {
            return false;
        }

        int offset =
                actual.size() - expected.size();

        for (int index = 0;index < expected.size();index++) {
            if (!Objects.equals(
                    actual.get(offset + index),
                    expected.get(index))) {

                return false;
            }
        }

        return true;
    }

    private Calculation calculate(
            String operation,
            FieldValueSet valueSet,
            FieldOption field) {

        long matchedCount =
                valueSet.values().size();

        long nullCount =
                valueSet.values()
                        .stream()
                        .filter(this::isNullOrBlank)
                        .count();

        List<JsonNode> nonNullValues =
                valueSet.values()
                        .stream()
                        .filter(value ->
                                !isNullOrBlank(value)
                        )
                        .toList();

        if ("COUNT".equals(operation)) {
            return new Calculation(
                    matchedCount,
                    nonNullValues.size(),
                    nullCount,
                    String.valueOf(
                            nonNullValues.size()
                    )
            );
        }

        if ("COUNT_DISTINCT".equals(operation)) {
            Set<String> distinctValues =
                    new LinkedHashSet<>();

            for (JsonNode value : nonNullValues) {
                distinctValues.add(
                        canonicalValue(value)
                );
            }

            return new Calculation(
                    matchedCount,
                    nonNullValues.size(),
                    nullCount,
                    String.valueOf(
                            distinctValues.size()
                    )
            );
        }

        List<BigDecimal> numbers =
                new ArrayList<>(
                        nonNullValues.size()
                );

        for (JsonNode value : nonNullValues) {
            try {
                numbers.add(
                        parseNumber(value)
                );
            } catch (NumberFormatException exception) {
                throw new AnalysisDataException(
                        "字段“"
                                + field.label()
                                + "”包含非数字值，"
                                + "为避免金额计算错误，本次没有忽略该记录。"
                                + "请检查字段类型或业务数据。"
                );
            }
        }

        if (numbers.isEmpty()) {
            throw new AnalysisDataException(
                    "字段“"
                            + field.label()
                            + "”没有可用于统计的数字。"
            );
        }

        BigDecimal value;

        switch (operation) {
            case "SUM" -> value =
                    numbers.stream()
                            .reduce(
                                    BigDecimal.ZERO,
                                    BigDecimal::add
                            );

            case "AVG" -> {
                BigDecimal sum =
                        numbers.stream()
                                .reduce(
                                        BigDecimal.ZERO,
                                        BigDecimal::add
                                );

                value = sum.divide(
                        BigDecimal.valueOf(
                                numbers.size()
                        ),
                        MathContext.DECIMAL128
                );
            }

            case "MIN" -> value =
                    numbers.stream()
                            .min(BigDecimal::compareTo)
                            .orElseThrow();

            case "MAX" -> value =
                    numbers.stream()
                            .max(BigDecimal::compareTo)
                            .orElseThrow();

            default -> throw new AnalysisDataException(
                    "当前统计方式暂不支持。"
            );
        }

        return new Calculation(
                matchedCount,
                numbers.size(),
                nullCount,
                formatNumber(value)
        );
    }

    private BigDecimal parseNumber(
            JsonNode value) {

        if (value.isNumber()) {
            return value.decimalValue();
        }

        /*
         * 兼容业务接口把金额返回为字符串的情况。
         * 这里只移除千分位逗号和空格，不自动转换金额单位。
         */
        String text =
                value.asText()
                        .trim()
                        .replace(",", "")
                        .replace(" ", "");

        return new BigDecimal(text);
    }

    private boolean isNullOrBlank(
            JsonNode value) {

        return value == null
                || value.isNull()
                || (value.isTextual()
                && !StringUtils.hasText(
                value.asText()
        ));
    }

    private String canonicalValue(
            JsonNode value) {

        if (value.isNumber()) {
            return formatNumber(
                    value.decimalValue()
            );
        }

        return value.asText().trim();
    }

    private String formatNumber(
            BigDecimal value) {

        BigDecimal normalized =
                value.stripTrailingZeros();

        if (normalized.scale() < 0) {
            normalized =
                    normalized.setScale(0);
        }

        return normalized.toPlainString();
    }

    /**
     * 将多个字段的统计结果渲染为一张固定表格。
     */
    private String renderMarkdown(String operation,List<StatisticResult> statistics,
                                   boolean dataComplete,boolean convertToTenThousandYuan) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("## 上一轮结果统计")
                .append("\n\n")
                .append("| 统计字段 | 统计方式 | 参与计算数量 | 统计结果 |")
                .append("\n")
                .append("|---|---|---:|---:|")
                .append("\n");

        for (StatisticResult statistic : statistics) {
            markdown.append("| ")
                    .append(escapeMarkdown(statistic.field().label()))
                    .append(" | ")
                    .append(operationLabel(operation))
                    .append(" | ")
                    .append(statistic.calculation().usedCount())
                    .append(" | **")
                    .append(formatStatisticValue(operation,statistic,convertToTenThousandYuan))
                    .append("** |")
                    .append("\n");
        }

        for (StatisticResult statistic : statistics) {
            if (statistic.calculation().nullCount() <= 0) {
                continue;
            }

            markdown.append("\n")
                    .append("> 字段“")
                    .append(escapeMarkdown(
                            statistic.field().label()
                    ))
                    .append("”有 ")
                    .append(statistic.calculation().nullCount())
                    .append(" 条记录为空，未参与计算。");
        }

        if (!dataComplete) {
            markdown.append("\n\n")
                    .append("> 注意：上一轮业务查询被标记为部分成功，")
                    .append("以上结果基于上一轮实际成功返回的全部数据计算。");
        }

        return markdown.toString();
    }
    /**
     * 判断用户是否明确要求将金额换算成万元。
     */
    private boolean requestsTenThousandYuan(
            AgentRequest request) {

        return request != null
                && StringUtils.hasText(request.getUserQuestion())
                && request.getUserQuestion().contains("万元");
    }

    /**
     * 金额字段原始单位为元时，按照用户要求确定性换算为万元。
     */
    private String formatStatisticValue(
            String operation,
            StatisticResult statistic,
            boolean convertToTenThousandYuan) {

        String originalValue = statistic.calculation().value();

        if (!convertToTenThousandYuan || "COUNT".equals(operation)
                || "COUNT_DISTINCT".equals(operation)
                || !isYuanAmountField(statistic.field())) {

            return originalValue;
        }

        BigDecimal tenThousandYuan =new BigDecimal(originalValue).movePointLeft(4);

        return formatNumber(tenThousandYuan)+ " 万元";
    }

    /**
     * 只有字段字典明确声明原始单位为元的金额字段才允许换算。
     */
    private boolean isYuanAmountField( FieldOption field) {
        return field != null
                && "amount".equalsIgnoreCase(field.format())
                && StringUtils.hasText(field.meaning())
                && field.meaning()
                .replace(" ", "")
                .contains("单位元");
    }
    private String operationLabel(
            String operation) {

        return switch (operation) {
            case "SUM" -> "求和";
            case "COUNT" -> "有效值计数";
            case "COUNT_DISTINCT" -> "去重计数";
            case "AVG" -> "平均值";
            case "MIN" -> "最小值";
            case "MAX" -> "最大值";
            default -> operation;
        };
    }

    private ResultArtifactAnalysisResult guidance(
            ResultArtifactSnapshot snapshot,
            String message) {

        return new ResultArtifactAnalysisResult(
                message,
                "需要补充统计条件",
                Boolean.TRUE.equals(
                        snapshot.artifact()
                                .getDataComplete()
                )
        );
    }

    private String resolveReportTitle(
            ResultArtifactSnapshot snapshot) {

        String workflowName =
                snapshot.artifact()
                        .getWorkflowName();

        return StringUtils.hasText(workflowName)
                ? workflowName + "统计结果"
                : "业务数据统计结果";
    }

    private String extractJson(
            String content) {

        int start =
                content.indexOf('{');

        int end =
                content.lastIndexOf('}');

        if (start < 0 || end < start) {
            throw new IllegalArgumentException(
                    "统计规划模型没有返回合法JSON"
            );
        }

        return content.substring(
                start,
                end + 1
        );
    }

    private String normalize(
            String value) {

        return StringUtils.hasText(value)
                ? value.trim()
                .toUpperCase(Locale.ROOT)
                : "";
    }

    private String trimToNull(
            String value) {

        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }

    private String escapeMarkdown(
            String value) {

        if (!StringUtils.hasText(value)) {
            return "-";
        }

        return value.replace("|", "\\|")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private record AnalysisPlan(
            String mode,
            String operation,
            // 兼容旧模型返回的单字段协议。
            String metricFieldId,
            // 新协议支持一次选择多个受控字段。
            List<String> metricFieldIds,
            Double confidence,
            String reason) {
    }

    /**
     * 只把受控字段目录交给规划模型。
     */
    private record FieldOption(
            String id,
            String capabilityCode,
            String fieldName,
            String label,
            String meaning,
            String format,
            String fieldPath,
            String fieldType,
            boolean aggregatable) {
    }

    private record LeafValue(
            List<String> pathTokens,
            JsonNode value) {
    }

    private record FieldValueSet(
            String structuralPath,
            List<JsonNode> values) {
    }

    private record Calculation(
            long matchedCount,
            long usedCount,
            long nullCount,
            String value) {
    }
    /**
     * 保存一个字段及其本地计算结果。
     */
    private record StatisticResult(
            FieldOption field,
            Calculation calculation) {
    }
    private static final class
    AnalysisDataException extends RuntimeException {

        private AnalysisDataException(
                String message) {
            super(message);
        }
    }
}