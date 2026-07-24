package org.example.ai.agent.workflow.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.parameter.CapabilityInputSchemaValidator;
import org.example.ai.agent.capability.parameter.CapabilityInputValidationResult;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.enums.WorkflowPlanStatus;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.workflow.entity.WorkflowDefinition;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.example.ai.agent.workflow.service.WorkflowDefinitionService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.compiler.GraphCapabilityCatalog;
import org.example.ai.agent.graph.config.CapabilityNodeConfig;
import org.example.ai.agent.graph.runtime.GraphExecutionContext;
import org.example.ai.agent.graph.runtime.GraphExecutionRequest;
import org.example.ai.agent.graph.runtime.GraphRuntimeExpressionResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 已发布工作流规划器。
 *
 * 分两次模型调用：
 * 1. 只选择工作流；
 * 2. 只提取选中工作流的输入参数。
 */
@Component
@RequiredArgsConstructor
public class WorkflowPlanner {

    private static final double MIN_CONFIDENCE = 0.75D;

    private final WorkflowDefinitionService workflowService;
    private final WorkflowRuntimeSnapshotResolver snapshotResolver;
    private final CapabilityInputSchemaValidator inputValidator;
    private final TrackedChatClientService chatClientService;
    private final ObjectMapper objectMapper;
    /**
     * 读取发布能力的副作用类型。
     */
    private final GraphCapabilityCatalog capabilityCatalog;

    /**
     * 复用 GraphSpec 原有受限表达式解析器。
     */
    private final GraphRuntimeExpressionResolver expressionResolver;

    public WorkflowPlan plan(String userQuestion, ModelCallContext sourceContext) {

        List<WorkflowDefinition> candidates =workflowService.listAgentCallableDefinitions();

        if (candidates.isEmpty()) {
            return notMatched(
                    "当前没有已发布工作流"
            );
        }

        WorkflowSelectionDecision decision =
                selectWorkflow(
                        userQuestion,
                        candidates,
                        sourceContext
                );

        if (!Boolean.TRUE.equals(decision.matched())) {
            return notMatched( decision.reason());
        }

        WorkflowDefinition selected =
                candidates.stream() .filter(item ->
                                Objects.equals(
                                        item.getWorkflowCode(),
                                        decision.workflowCode()
                                ))
                        .findFirst().orElseThrow(() ->
                                new BusinessException(
                                        400,
                                        "模型选择了候选列表之外的工作流"
                                )
                        );

        double confidence = decision.confidence() == null
                        ? 0D
                        : decision.confidence();

        if (confidence < MIN_CONFIDENCE) {
            return WorkflowPlan.builder()
                    .status( WorkflowPlanStatus.NEED_CLARIFY)
                    .workflowCode(selected.getWorkflowCode())
                    .workflowName( selected.getWorkflowName())
                    .confidence(confidence)
                    .reason("工作流匹配置信度不足")
                    .clarifyQuestion(StringUtils.hasText(decision.clarifyQuestion())
                                    ? decision.clarifyQuestion()
                                    : "请确认需要执行的具体业务查询。")
                    .build();
        }

        PublishedWorkflow published =snapshotResolver.resolveByCode(selected.getWorkflowCode());
        /*
         * 编译器已经保证 WRITE 工作流只有一个能力节点。
         */
        CompiledGraphNode writeNode = findWriteNode(published);

        CapabilityNodeConfig writeConfig =
                writeNode != null
                        && writeNode.config()
                        instanceof CapabilityNodeConfig config
                        ? config
                        : null;

        String sideEffect =writeConfig == null
                        ? "READ"
                        : "WRITE";

        WorkflowParameterExtraction extraction =extractParameters(
                        userQuestion,
                        published,
                        sourceContext);

        String schemaJson =writeJson(published.inputSchema());

        CapabilityInputValidationResult validation =
                inputValidator.validate(
                        schemaJson,
                        extraction.input()
                );

        if (!validation.isValid()) {
            return WorkflowPlan.builder()
                    .status(
                            WorkflowPlanStatus
                                    .NEED_CLARIFY
                    )
                    .workflowCode(
                            selected.getWorkflowCode()
                    )
                    .workflowName(
                            published.compiledGraph()
                                    .name()
                    )
                    .versionId(
                            published.version().getId()
                    )
                    .confidence(confidence)
                    .reason("工作流参数未通过Schema校验")
                    .clarifyQuestion(
                            buildClarifyQuestion(
                                    validation
                            )
                    )
                    .sideEffect(sideEffect)
                    .actionCapabilityCode(
                            writeConfig == null
                                    ? null
                                    : writeConfig.capabilityCode()
                    )
                    .actionCapabilityName(
                            writeNode == null
                                    ? null
                                    : writeNode.name()
                    )
                    .build();
        }
        Map<String, Object> actionInput =new LinkedHashMap<>();
        if (writeConfig != null) {
            /*
             * WRITE 工作流只有 START → WRITE → END，
             * 因此只能读取 $input，不存在上游变量。
             */
            GraphExecutionContext context =
                    GraphExecutionContext.create(
                            GraphExecutionRequest.builder()
                                    .runId(
                                            sourceContext == null
                                                    ? null
                                                    : sourceContext
                                                    .getRunId()
                                    )
                                    .userId(
                                            sourceContext == null
                                                    ? null
                                                    : sourceContext
                                                    .getUserId()
                                    )
                                    .input(
                                            validation
                                                    .getSanitizedInput()
                                    )
                                    .executionPath("root")
                                    .build()
                    );
            actionInput =expressionResolver.resolveMap( writeConfig.inputMapping(),context );
        }
        return WorkflowPlan.builder()
                .status(WorkflowPlanStatus.READY)
                .workflowCode(selected.getWorkflowCode() )
                .workflowName(published.compiledGraph().name())
                .versionId(published.version().getId())
                .input(validation.getSanitizedInput())
                .confidence(confidence)
                .reason(decision.reason())
                .sideEffect(sideEffect)
                .actionCapabilityCode(
                        writeConfig == null
                                ? null
                                : writeConfig.capabilityCode()
                ).actionCapabilityName(writeNode == null
                                ? null
                                : writeNode.name()
                ).actionInput(actionInput)
                .build();
    }

    private WorkflowSelectionDecision selectWorkflow(
            String userQuestion,
            List<WorkflowDefinition> candidates,
            ModelCallContext sourceContext) {

        StringBuilder candidateText =
                new StringBuilder();

        for (WorkflowDefinition candidate :
                candidates) {

            candidateText.append("- workflowCode：")
                    .append(
                            candidate.getWorkflowCode()
                    )
                    .append("\n")
                    .append("  名称：")
                    .append(
                            candidate.getWorkflowName()
                    )
                    .append("\n")
                    .append("  用途：")
                    .append(
                            candidate.getDescription()
                    )
                    .append("\n\n");
        }

        String systemPrompt = """
                你是企业PM系统的工作流选择器。

                规则：
                1. 只能从候选工作流中选择。
                2. 不允许生成工作流输入参数。
                3. 单接口查询优先返回matched=false，由后端走普通能力。
                4. 只有用户需要多步骤、批量或综合查询时才选择工作流。
                5. 禁止编造候选列表之外的workflowCode。
                6. 只输出JSON，不输出Markdown。

                输出格式：
                {
                  "matched": true,
                  "workflowCode": "候选编码",
                  "confidence": 0.90,
                  "reason": "选择原因",
                  "clarifyQuestion": null
                }
                """;

        String userPrompt = """
                用户问题：
                %s

                候选工作流：
                %s
                """.formatted(
                        userQuestion,
                        candidateText
                );

        ChatResponse response =
                chatClientService.call(
                        buildContext(
                                sourceContext,
                                ModelCallType
                                        .WORKFLOW_PLANNER
                        ),
                        systemPrompt,
                        userPrompt,
                        ChatOptions.builder()
                                .temperature(0.0D)
                                .topP(0.1D)
                );

        return readJson(
                response,
                WorkflowSelectionDecision.class
        );
    }

    private WorkflowParameterExtraction
    extractParameters(
            String userQuestion,
            PublishedWorkflow workflow,
            ModelCallContext sourceContext) {

        String systemPrompt = """
                你是企业工作流参数提取器。

                规则：
                1. 只能从用户原话提取参数。
                2. 不得补充用户没有提供的项目。
                3. 不得生成Schema之外的字段。
                4. 不得修改workflowCode。
                5. 只输出JSON，不输出Markdown。

                输出格式：
                {
                  "input": {},
                  "reason": "提取说明"
                }
                """;

        String userPrompt = """
                用户问题：
                %s

                工作流名称：
                %s

                工作流输入Schema：
                %s
                """.formatted(
                        userQuestion,
                        workflow.compiledGraph().name(),
                        writeJson(workflow.inputSchema())
                );

        ChatResponse response =
                chatClientService.call(
                        buildContext(
                                sourceContext,
                                ModelCallType.WORKFLOW_PARAMETER_EXTRACTOR
                        ),
                        systemPrompt,
                        userPrompt,
                        ChatOptions.builder()
                                .temperature(0.0D)
                                .topP(0.1D)
                );

        return readJson(
                response,
                WorkflowParameterExtraction.class
        );
    }

    private ModelCallContext buildContext(
            ModelCallContext source,
            ModelCallType callType) {

        return ModelCallContext.builder()
                .runId(
                        source == null
                                ? null
                                : source.getRunId()
                )
                .conversationId(
                        source == null
                                ? null
                                : source.getConversationId()
                )
                .userId(
                        source == null
                                ? null
                                : source.getUserId()
                )
                .callType(callType)
                .callSequence(1)
                .build();
    }

    private <T> T readJson(
            ChatResponse response,
            Class<T> type) {

        if (response == null
                || response.getResult() == null
                || response.getResult()
                .getOutput() == null) {
            throw new BusinessException(
                    400,
                    "工作流规划模型没有返回结果"
            );
        }

        String content =
                response.getResult()
                        .getOutput()
                        .getText();

        if (!StringUtils.hasText(content)) {
            throw new BusinessException(
                    400,
                    "工作流规划模型返回内容为空"
            );
        }

        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');

        if (start < 0 || end < start) {
            throw new BusinessException(
                    400,
                    "工作流规划模型没有返回合法JSON"
            );
        }

        try {
            return objectMapper.readValue(
                    content.substring(
                            start,
                            end + 1
                    ),
                    type
            );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    400,
                    "工作流规划模型JSON解析失败"
            );
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(
                    value
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "工作流配置序列化失败",
                    exception
            );
        }
    }

    private String buildClarifyQuestion(
            CapabilityInputValidationResult result) {

        StringBuilder builder =
                new StringBuilder(
                        "已确定需要执行工作流，但还需要补充参数。"
                );

        if (!result.getMissingParameters()
                .isEmpty()) {
            builder.append("缺少：")
                    .append(
                            String.join(
                                    "、",
                                    result.getMissingParameters()
                            )
                    )
                    .append("。");
        }

        if (!result.getValidationErrors()
                .isEmpty()) {
            builder.append("参数问题：")
                    .append(
                            String.join(
                                    "；",
                                    result.getValidationErrors()
                            )
                    )
                    .append("。");
        }

        return builder.toString();
    }

    private WorkflowPlan notMatched(
            String reason) {

        return WorkflowPlan.builder()
                .status(
                        WorkflowPlanStatus
                                .NOT_MATCHED
                )
                .input(new LinkedHashMap<>())
                .confidence(0D)
                .reason(reason)
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    private record WorkflowSelectionDecision(
            Boolean matched,
            String workflowCode,
            Double confidence,
            String reason,
            String clarifyQuestion) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    private record WorkflowParameterExtraction(
            Map<String, Object> input,
            String reason) {

        private WorkflowParameterExtraction {
            input = input == null
                    ? Map.of()
                    : Map.copyOf(input);
        }
    }
    /**
     * 查找已发布工作流中的 WRITE 节点。
     */
    private CompiledGraphNode findWriteNode(
            PublishedWorkflow workflow) {

        for (CompiledGraphNode node : workflow.compiledGraph().nodesById().values()) {
            if (!(node.config() instanceof CapabilityNodeConfig config)) {
                continue;
            }
            if ("WRITE".equalsIgnoreCase(capabilityCatalog.sideEffect(config.capabilityCode()))) {
                return node;
            }
        }
        return null;
    }
}