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
import com.fasterxml.jackson.databind.JsonNode;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.ui.CapabilityOptionService;
import org.example.ai.agent.capability.vo.CapabilityOptionResolution;

import java.util.Optional;
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
    /**
     * 读取WRITE能力发布快照。
     */
    private final CapabilityDefinitionService  capabilityDefinitionService;

    /**
     * 解析远程下拉中文名称和真实ID。
     */
    private final CapabilityOptionService capabilityOptionService;

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
        CapabilityDefinition writeCapability =
                writeConfig == null? null
                        : getRequiredWriteCapability(
                        writeConfig.capabilityCode() );

        JsonNode actionInputSchema = writeCapability == null
                        ? null
                        : readSchema(writeCapability.getInputSchemaJson());
        WorkflowParameterExtraction extraction =extractParameters(
                        userQuestion,
                        published,
                        sourceContext);
        /*
         * 即使工作流输入还没有通过校验，
         * 也先按受限inputMapping生成WRITE表单初始值。
         *
         * 这里只解析$input，不执行能力。
         */
        Map<String, Object> rawActionInput =
                writeConfig == null? new LinkedHashMap<>()
                        : resolveWriteInput(
                        writeConfig,
                        extraction.input(),
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
                    ).input(
                            writeConfig == null
                                    ? extraction.input()
                                    : rawActionInput
                    )
                    .actionCapabilityVersionId(
                            writeCapability == null
                                    ? null
                                    : writeCapability.getActiveVersionId()
                    )
                    .actionInputSchema(actionInputSchema)
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
                .actionCapabilityVersionId(writeCapability == null ? null : writeCapability.getActiveVersionId())
                .actionInputSchema(actionInputSchema)
                .build();
    }

    /**
     * 处理前端提交的WRITE动态表单。
     *
     * 该入口不调用大模型重新选择工作流，
     * 只允许精确恢复已经发布的工作流和能力。
     */
    public WorkflowPlan planActionForm(
            Map<String, Object> submission,
            ModelCallContext sourceContext,
            String authorization) {

        if (submission == null) {
            throw new BusinessException(
                    400,
                    "WRITE表单提交不能为空"
            );
        }

        String workflowCode =
                requiredSubmissionText(
                        submission,
                        "workflowCode"
                );

        String capabilityCode =
                requiredSubmissionText(
                        submission,
                        "capabilityCode"
                );

        Long workflowVersionId =
                requiredSubmissionLong(
                        submission,
                        "workflowVersionId"
                );

        Long capabilityVersionId =
                requiredSubmissionLong(
                        submission,
                        "capabilityVersionId"
                );

        Map<String, Object> rawInput =
                readSubmissionInput(
                        submission.get("input")
                );

        WorkflowDefinition selected =
                workflowService
                        .listAgentCallableDefinitions()
                        .stream()
                        .filter(item ->
                                workflowCode.equals(
                                        item.getWorkflowCode()
                                )
                        )
                        .findFirst()
                        .orElseThrow(() ->
                                new BusinessException(
                                        404,
                                        "WRITE工作流不存在、未启用或未发布："
                                                + workflowCode
                                )
                        );

        PublishedWorkflow published =
                snapshotResolver.resolveByCode(
                        workflowCode
                );

        if (!Objects.equals(
                workflowVersionId,
                published.version().getId()
        )) {
            throw new BusinessException(
                    409,
                    "工作流版本已经变化，请重新打开操作表单"
            );
        }

        CompiledGraphNode writeNode = findWriteNode(published);

        if (writeNode == null || !(writeNode.config() instanceof CapabilityNodeConfig writeConfig)) {
            throw new BusinessException(
                    400,
                    "工作流中没有合法的WRITE能力节点"
            );
        }

        if (!capabilityCode.equals(writeConfig.capabilityCode())) {
            throw new BusinessException(
                    400,
                    "提交的WRITE能力不属于当前工作流"
            );
        }

        CapabilityDefinition writeCapability = getRequiredWriteCapability( capabilityCode);

        if (!Objects.equals(
                capabilityVersionId,
                writeCapability.getActiveVersionId()
        )) {
            throw new BusinessException(
                    409,
                    "WRITE能力版本已经变化，请重新打开操作表单"
            );
        }

        String schemaJson =
                writeCapability.getInputSchemaJson();

        JsonNode schemaNode = readSchema(schemaJson);

        CapabilityOptionResolution resolution =
                capabilityOptionService.resolveInput(
                        capabilityCode,
                        rawInput,
                        sourceContext == null
                                ? null
                                : sourceContext.getUserId(),
                        authorization
                );

        if (!resolution.isReady()) {
            return WorkflowPlan.builder()
                    .status(
                            WorkflowPlanStatus.NEED_CLARIFY
                    )
                    .workflowCode(workflowCode)
                    .workflowName(
                            published.compiledGraph().name()
                    )
                    .versionId(workflowVersionId)
                    .input(rawInput)
                    .confidence(1D)
                    .reason(
                            "WRITE表单选项需要用户确认"
                    )
                    .clarifyQuestion(
                            resolution.getClarifyQuestion()
                    )
                    .sideEffect("WRITE")
                    .actionCapabilityCode(
                            capabilityCode
                    )
                    .actionCapabilityName(
                            writeNode.name()
                    )
                    .actionCapabilityVersionId(
                            capabilityVersionId
                    )
                    .actionInputSchema(schemaNode)
                    .actionDisplayInput(
                            resolution.getDisplayInput()
                    )
                    .build();
        }

        CapabilityInputValidationResult validation =
                inputValidator.validate(
                        schemaJson,
                        resolution.getRequestInput()
                );

        if (!validation.isValid()) {
            return WorkflowPlan.builder()
                    .status(
                            WorkflowPlanStatus.NEED_CLARIFY
                    )
                    .workflowCode(workflowCode)
                    .workflowName(
                            published.compiledGraph().name()
                    )
                    .versionId(workflowVersionId)
                    .input(rawInput)
                    .confidence(1D)
                    .reason(
                            "WRITE表单参数未通过Schema校验"
                    )
                    .clarifyQuestion(
                            buildClarifyQuestion(validation)
                    )
                    .sideEffect("WRITE")
                    .actionCapabilityCode(
                            capabilityCode
                    )
                    .actionCapabilityName(
                            writeNode.name()
                    )
                    .actionCapabilityVersionId(
                            capabilityVersionId
                    )
                    .actionInputSchema(schemaNode)
                    .actionDisplayInput(
                            resolution.getDisplayInput()
                    )
                    .build();
        }

        return WorkflowPlan.builder()
                .status(WorkflowPlanStatus.READY)
                .workflowCode(workflowCode)
                .workflowName(
                        published.compiledGraph().name()
                )
                .versionId(workflowVersionId)
                .input(
                        validation.getSanitizedInput()
                )
                .confidence(1D)
                .reason(
                        "WRITE动态表单已通过校验"
                )
                .sideEffect("WRITE")
                .actionCapabilityCode(
                        capabilityCode
                )
                .actionCapabilityName(
                        writeNode.name()
                )
                .actionCapabilityVersionId(
                        capabilityVersionId
                )
                .actionInputSchema(schemaNode)
                /*
                 * PendingAction只保存该真实请求参数。
                 */
                .actionInput(
                        validation.getSanitizedInput()
                )
                /*
                 * 中文名称只用于预览。
                 */
                .actionDisplayInput(
                        resolution.getDisplayInput()
                )
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

    /**
     * 使用现有受限表达式解析器生成WRITE表单初始值。
     */
    private Map<String, Object> resolveWriteInput(
            CapabilityNodeConfig writeConfig,
            Map<String, Object> rawInput,
            ModelCallContext sourceContext) {

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
                                        rawInput == null
                                                ? Map.of()
                                                : rawInput
                                )
                                .executionPath("root")
                                .build()
                );

        return expressionResolver.resolveMap(
                writeConfig.inputMapping(),
                context
        );
    }

    /**
     * 读取已启用、已发布、需要确认的WRITE能力。
     */
    private CapabilityDefinition getRequiredWriteCapability(
            String capabilityCode) {

        CapabilityDefinition capability =
                capabilityDefinitionService
                        .getEnabledByCode(
                                capabilityCode
                        );

        if (capability == null) {
            throw new BusinessException(
                    404,
                    "WRITE能力不存在、未启用或未发布："
                            + capabilityCode
            );
        }

        if (!"WRITE".equalsIgnoreCase(
                capability.getSideEffect()
        ) || !Boolean.TRUE.equals(
                capability.getRequireConfirm()
        )) {
            throw new BusinessException(
                    400,
                    "能力不是需要确认的WRITE能力："
                            + capabilityCode
            );
        }

        return capability;
    }

    private JsonNode readSchema(String schemaJson) {
        try {
            JsonNode schema =
                    objectMapper.readTree(schemaJson);

            if (schema == null || !schema.isObject()) {
                throw new BusinessException(
                        400,
                        "WRITE能力inputSchemaJson必须是JSON对象"
                );
            }

            return schema;
        } catch (BusinessException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    400,
                    "WRITE能力inputSchemaJson不是合法JSON"
            );
        }
    }

    private String requiredSubmissionText(
            Map<String, Object> submission,
            String fieldName) {

        Object value =
                submission.get(fieldName);

        String textValue =
                value == null
                        ? ""
                        : String.valueOf(value)
                        .trim();

        if (!StringUtils.hasText(textValue)) {
            throw new BusinessException(
                    400,
                    "WRITE表单缺少字段："
                            + fieldName
            );
        }

        return textValue;
    }

    private Long requiredSubmissionLong(
            Map<String, Object> submission,
            String fieldName) {

        Object value =
                submission.get(fieldName);

        if (value instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.valueOf(
                    String.valueOf(value)
            );
        } catch (Exception exception) {
            throw new BusinessException(
                    400,
                    "WRITE表单版本字段不合法："
                            + fieldName
            );
        }
    }

    private Map<String, Object> readSubmissionInput(
            Object value) {

        if (!(value instanceof Map<?, ?> source)) {
            throw new BusinessException(
                    400,
                    "WRITE表单input必须是JSON对象"
            );
        }

        Map<String, Object> result =
                new LinkedHashMap<>();

        source.forEach((key, child) ->
                result.put(
                        String.valueOf(key),
                        child
                )
        );

        return result;
    }
}