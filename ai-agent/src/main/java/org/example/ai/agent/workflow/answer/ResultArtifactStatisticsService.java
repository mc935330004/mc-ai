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
    public Optional<ResultArtifactAnalysisResult>
    tryAnalyze(
            AgentRequest request,
            String runId,
            ResultArtifactSnapshot snapshot) {

        List<WorkflowAnswerFieldContext> fields =
                readFieldSemantics(
                        snapshot.fieldSemanticsJson()
                );

        List<FieldOption> fieldOptions =
                buildFieldOptions(fields);

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

        FieldOption metricField =
                findField(
                        fieldOptions,
                        plan.metricFieldId()
                );

        if (metricField == null
                || !StringUtils.hasText(
                metricField.fieldPath())) {

            return Optional.of(
                    guidance(
                            snapshot,
                            "没有在上一轮字段语义中找到可定位的统计字段。"
                                    + "请检查字段字典的字段路径并重新执行查询。"
                    )
            );
        }

        /*
         * 求和、平均值、最大值、最小值只能使用允许聚合的字段。
         * COUNT和COUNT_DISTINCT只读取字段出现次数，不强制聚合标志。
         */
        if (!"COUNT".equals(operation)
                && !"COUNT_DISTINCT".equals(operation)
                && !metricField.aggregatable()) {

            return Optional.of(
                    guidance(
                            snapshot,
                            "字段“"
                                    + metricField.label()
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

            JsonNode resultNode =
                    payload.get("result");

            if (resultNode == null
                    || resultNode.isNull()
                    || resultNode.isMissingNode()) {

                return Optional.of(
                        guidance(
                                snapshot,
                                "上一轮查询没有返回可以统计的业务数据。"
                        )
                );
            }

            FieldValueSet valueSet =
                    extractFieldValues(
                            resultNode,
                            metricField
                    );

            Calculation calculation =
                    calculate(
                            operation,
                            valueSet,
                            metricField
                    );

            return Optional.of(
                    new ResultArtifactAnalysisResult(
                            renderMarkdown(
                                    operation,
                                    metricField,
                                    calculation,
                                    Boolean.TRUE.equals(
                                            snapshot.artifact()
                                                    .getDataComplete()
                                    )
                            ),
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
                trimToNull(
                        original.metricFieldId()
                ),
                original.confidence(),
                trimToNull(original.reason())
        );
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

                1. metricFieldId只能选择字段目录中的字段ID。
                2. SUM、AVG、MIN、MAX必须选择aggregatable=true的字段。
                3. 不得自己编造字段ID、字段名称或者字段路径。
                4. 不得计算最终金额。
                5. 不得输出Markdown。
                6. 只输出一个JSON对象。

                输出格式：

                {
                  "mode": "STATISTICS",
                  "operation": "SUM",
                  "metricFieldId": "F3",
                  "confidence": 0.98,
                  "reason": "用户要求汇总本次结算金额"
                }
                """;
    }

    private String buildUserPrompt(
            AgentRequest request,
            List<FieldOption> fieldOptions)
            throws Exception {

        return """
                用户问题：
                %s

                可选择字段目录：
                %s
                """.formatted(
                request.getEffectiveQuestion(),
                objectMapper.writeValueAsString(
                        fieldOptions
                )
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
            matches = leaves.stream()
                    .filter(leaf ->
                            !leaf.pathTokens().isEmpty()
                                    && field.fieldName()
                                    .equals(
                                            leaf.pathTokens()
                                                    .get(
                                                            leaf.pathTokens()
                                                                    .size() - 1
                                                    )
                                    )
                    )
                    .toList();
        }

        if (matches.isEmpty()) {
            throw new AnalysisDataException(
                    "上一轮结果中没有找到字段“"
                            + field.label()
                            + "”。请检查字段字典路径后重新查询。"
            );
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
                    "字段“"
                            + field.label()
                            + "”在上一轮结果中对应多个数据结构，"
                            + "为避免重复统计，本次没有计算。"
                            + "请为需要统计的字段配置更精确的字段路径。"
            );
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

    private String renderMarkdown(
            String operation,
            FieldOption field,
            Calculation calculation,
            boolean dataComplete) {

        StringBuilder markdown =
                new StringBuilder();

        markdown.append("## 上一轮结果统计")
                .append("\n\n")
                .append("| 统计项目 | 统计结果 |")
                .append("\n")
                .append("|---|---:|")
                .append("\n")
                .append("| 统计字段 | ")
                .append(escapeMarkdown(field.label()))
                .append(" |")
                .append("\n")
                .append("| 统计方式 | ")
                .append(operationLabel(operation))
                .append(" |")
                .append("\n")
                .append("| 参与计算数量 | ")
                .append(calculation.usedCount())
                .append(" |")
                .append("\n")
                .append("| 统计结果 | **")
                .append(calculation.value())
                .append("** |")
                .append("\n");

        if (calculation.nullCount() > 0) {
            markdown.append("\n")
                    .append("> 有 ")
                    .append(calculation.nullCount())
                    .append(" 条记录的该字段为空，"
                            + "未参与计算，但没有被静默忽略。");
        }

        if (!dataComplete) {
            markdown.append("\n\n")
                    .append("> 注意：上一轮业务查询被标记为部分成功，")
                    .append("以上结果基于上一轮实际成功返回的全部数据计算。");
        }

        return markdown.toString();
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
            String metricFieldId,
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

    private static final class
    AnalysisDataException
            extends RuntimeException {

        private AnalysisDataException(
                String message) {
            super(message);
        }
    }
}