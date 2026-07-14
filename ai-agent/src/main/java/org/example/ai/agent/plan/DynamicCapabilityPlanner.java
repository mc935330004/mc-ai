package org.example.ai.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.evaluation.service.CapabilityRouteAuditService;
import org.example.ai.agent.capability.parameter.CapabilityInputSchemaValidator;
import org.example.ai.agent.capability.parameter.CapabilityInputValidationResult;
import org.example.ai.agent.capability.parameter.CapabilityParameterExtractionResult;
import org.example.ai.agent.capability.parameter.CapabilityParameterExtractor;
import org.example.ai.agent.capability.routing.CapabilityCandidate;
import org.example.ai.agent.capability.routing.CapabilityCandidateRetriever;
import org.example.ai.agent.capability.routing.CapabilitySelectionGuard;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * 动态业务能力规划器。
 *
 * 第一阶段执行流程：
 *
 * 1. 从已发布能力中初步召回 Top-K
 * 2. 只把 Top-K 候选交给大模型
 * 3. 大模型返回最终能力、置信度和备选能力
 * 4. 后端安全闸门检查置信度和候选分差
 * 5. 检查通过后才生成后续业务执行计划
 *
 * 注意：
 * 第一阶段仍然由同一次模型调用生成接口参数。
 * 第二阶段会进一步把“选择能力”和“参数提取”拆成两次调用。
 */
@Component
@RequiredArgsConstructor
public class DynamicCapabilityPlanner {

    private final CapabilityDefinitionService capabilityDefinitionService;
    private final CapabilityCandidateRetriever candidateRetriever;
    private final CapabilitySelectionGuard selectionGuard;
    private final ObjectMapper objectMapper;
    private final TrackedChatClientService trackedChatClientService;
    private final CapabilityRouteAuditService routeAuditService;
    /**
     * 已选能力的参数提取器。
     */
    private final CapabilityParameterExtractor parameterExtractor;

    /**
     * 接口参数确定性校验器。
     */
    private final CapabilityInputSchemaValidator inputSchemaValidator;
    /**
     * 根据用户问题选择业务能力。
     */
    public DynamicCapabilityPlan plan(
            String userQuestion,
            ModelCallContext callContext) {
        long planningStartTime =System.currentTimeMillis();

        List<CapabilityCandidate> candidates = List.of();
        /*
         * 第一步：本地召回候选能力。
         *
         * 不再把数据库中全部能力直接交给大模型。
         */
         candidates =
                candidateRetriever.retrieve(userQuestion);

        if (candidates.isEmpty()) {
            DynamicCapabilityPlan result = unmatched("没有召回到相关业务能力",
                            "当前没有找到明确匹配的业务接口，请补充具体业务对象、查询条件和期望结果。" );

            return auditedReturn(
                    callContext,
                    userQuestion,
                    candidates,
                    result,
                    planningStartTime);
        }

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(
                userQuestion,
                candidates
        );

        /*
         * Planner 使用单次请求专属低随机性参数。
         *
         * 不修改全局回答模型配置。
         */
        ChatOptions.Builder<?> plannerOptions =
                ChatOptions.builder()
                        .temperature(0.0D)
                        .topP(0.1D);

        ChatResponse response =
                trackedChatClientService.call(
                        callContext,
                        systemPrompt,
                        userPrompt,
                        plannerOptions
                );

        String content =
                response.getResult()
                        .getOutput()
                        .getText();

        try {
            String json = extractJson(content);

            DynamicCapabilityPlan plan =
                    objectMapper.readValue(
                            json,
                            DynamicCapabilityPlan.class
                    );

            /*
             * 即使模型违规输出 input，
             * 能力选择阶段也必须强制清空。
             */
            plan.setInput(new LinkedHashMap<>());

            if (!plan.isMatched()) {
                plan.setCapabilityCode(null);
                return auditedReturn(
                        callContext,
                        userQuestion,
                        candidates,
                        plan,
                        planningStartTime
                );
            }
            /*
             * 校验模型选择结果，并取得数据库中的真实能力定义。
             */
            CapabilityDefinition selectedCapability =
                    validateSelectedCapability(
                            plan,
                            candidates
                    );
            /*
             * 执行置信度和分差闸门。
             */
            plan = selectionGuard.guard(
                    plan,
                    candidates
            );

            if (!plan.isMatched()) {
                return auditedReturn(
                        callContext,
                        userQuestion,
                        candidates,
                        plan,
                        planningStartTime
                );
            }

            /*
             * 第二次模型调用：
             * 只针对已经确定的唯一能力提取参数。
             */
            ModelCallContext parameterContext = buildParameterContext(callContext);

            CapabilityParameterExtractionResult extractionResult =
                    parameterExtractor.extract(
                            userQuestion,
                            selectedCapability,
                            parameterContext
                    );
            /*
             * 模型输出不能直接写入 plan。
             * 必须先经过 JSON Schema 白名单、类型和必填校验。
             */
            CapabilityInputValidationResult validationResult =
                    inputSchemaValidator.validate(
                            selectedCapability.getInputSchemaJson(),
                            extractionResult.getInput());

            if (!validationResult.isValid()) {
                return convertToClarify(
                        plan,
                        selectedCapability,
                        validationResult
                );
            }

            /*
             * 只有 sanitizedInput 能进入后续 ToolExecutor。
             */
            plan.setInput(
                    validationResult.getSanitizedInput()
            );
            return auditedReturn(
                    callContext,
                    userQuestion,
                    candidates,
                    plan,
                    planningStartTime
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            routeAuditService.recordFailure(
                    callContext,
                    userQuestion,
                    candidates,
                    exception.getMessage(),
                    System.currentTimeMillis()- planningStartTime);
            throw new BusinessException(
                    400,
                    "动态能力规划失败，模型返回内容："
                            + content
            );
        }
    }

    /**
     * 构建能力精排系统提示词。
     */
    private String buildSystemPrompt() {
        return """
            你是企业 PM 系统的业务能力选择器。

            你的唯一任务是从候选能力中选择最匹配的 capabilityCode。
            不需要生成接口参数，不需要回答用户问题。

            必须遵守以下规则：

            1. 只能从【候选业务能力】中选择 capabilityCode。
            2. 禁止编造候选列表之外的 capabilityCode。
            3. 候选能力都不明确匹配时，返回 matched=false。
            4. 必须区分列表、详情、统计、创建、修改等不同用途。
            5. confidence 范围为 0 到 1。
            6. 候选能力超过一个时，alternatives 至少返回第二候选。
            7. 不允许输出 input，不允许生成接口参数。
            8. 只允许输出 JSON，不允许输出 Markdown。
            9. 召回分数只用于候选初筛。最终必须根据用户真实意图和能力用途判断，禁止仅选择分数最高的能力

            明确匹配时输出：

            {
              "matched": true,
              "capabilityCode": "候选能力中的完整编码",
              "confidence": 0.86,
              "alternatives": [
                {
                  "capabilityCode": "第二候选能力编码",
                  "score": 0.55,
                  "rejectReason": "没有选择该能力的原因"
                }
              ],
              "reason": "选择最终能力的原因",
              "clarifyQuestion": null
            }

            无法明确选择时输出：

            {
              "matched": false,
              "capabilityCode": null,
              "confidence": 0.0,
              "alternatives": [],
              "reason": "无法明确选择的原因",
              "clarifyQuestion": "需要用户确认的问题"
            }
            """;
    }

    /**
     * 只把本地召回的 Top-K 候选交给模型。
     */
    private String buildUserPrompt(String userQuestion, List<CapabilityCandidate> candidates) {

        StringBuilder builder = new StringBuilder();
        builder.append("【用户问题】\n")
                .append(userQuestion)
                .append("\n\n");
        builder.append("【候选业务能力】\n");

        for (CapabilityCandidate candidate : candidates) {
            CapabilityDefinition capability =
                    candidate.getCapability();

            builder.append("- 能力编码：")
                    .append(capability.getCapabilityCode())
                    .append("\n");

            builder.append("  能力名称：")
                    .append(capability.getCapabilityName())
                    .append("\n");

            builder.append("  业务域：")
                    .append(capability.getDomain())
                    .append("\n");

            builder.append("  模块名称：")
                    .append(capability.getModuleName())
                    .append("\n");

            builder.append("  适用场景：")
                    .append(capability.getDescription())
                    .append("\n");

            builder.append("  操作类型：")
                    .append(capability.getSideEffect())
                    .append("\n");

            builder.append("  初步召回分数：")
                    .append(candidate.getRecallScore())
                    .append("\n");

            builder.append("  初步命中片段：")
                    .append(candidate.getMatchedTerms())
                    .append("\n\n");
            builder.append("  关键词召回分数：")
                    .append(candidate.getKeywordScore())
                    .append("\n");

            builder.append("  向量召回分数：")
                    .append(candidate.getVectorScore())
                    .append("\n");

            builder.append("  融合召回分数：")
                    .append(candidate.getRecallScore())
                    .append("\n");

            builder.append("  召回来源：")
                    .append(candidate.getSources())
                    .append("\n");
        }

        return builder.toString();
    }

    /**
     * 校验模型选择的能力。
     */
    private CapabilityDefinition  validateSelectedCapability(
            DynamicCapabilityPlan plan,
            List<CapabilityCandidate> candidates) {

        if (!StringUtils.hasText(plan.getCapabilityCode())) {
            throw new BusinessException( 400,
                    "动态能力规划失败：模型没有返回 capabilityCode");
        }

        boolean selectedFromCandidates =
                candidates.stream()
                        .map(CapabilityCandidate::getCapability)
                        .map(CapabilityDefinition::getCapabilityCode)
                        .anyMatch(plan.getCapabilityCode()::equals);

        if (!selectedFromCandidates) {
            throw new BusinessException( 400,
                    "动态能力规划失败：模型选择了候选列表之外的能力："+ plan.getCapabilityCode());
        }

        CapabilityDefinition capability =capabilityDefinitionService.getEnabledByCode( plan.getCapabilityCode());

        if (capability == null) {
            throw new BusinessException( 400,
                    "动态能力规划失败：能力不存在、未启用或未发布："+ plan.getCapabilityCode() );
        }

        /*
         * 安全属性只能信任数据库。
         * 不允许使用模型自行生成的 sideEffect 和 requireConfirm。
         */
        plan.setCapabilityName(
                capability.getCapabilityName()
        );

        plan.setSideEffect(
                capability.getSideEffect()
        );

        plan.setRequireConfirm(
                Boolean.TRUE.equals(
                        capability.getRequireConfirm()
                )
        );
        plan.setInput(new LinkedHashMap<>());
        return capability;
    }


    /**
     * 兼容部分模型偶尔返回 ```json 代码块的情况。
     *
     * 最终仍然只截取第一个 JSON 对象。
     */
    private String extractJson(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException( 400,
                    "动态能力规划失败：模型返回内容为空");
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');

        if (start < 0 || end < start) {
            throw new BusinessException( 400,"动态能力规划失败：模型没有返回合法 JSON" );
        }
        return content.substring(start, end + 1);
    }

    /**
     * 构建未匹配结果。
     */
    private DynamicCapabilityPlan unmatched( String reason,String clarifyQuestion) {

        DynamicCapabilityPlan plan = new DynamicCapabilityPlan();

        plan.setMatched(false);
        plan.setCapabilityCode(null);
        plan.setConfidence(0D);
        plan.setInput(new LinkedHashMap<>());
        plan.setReason(reason);
        plan.setClarifyQuestion(clarifyQuestion);

        return plan;
    }

    /**
     * 构建第二次模型调用的上下文。
     *
     * 与能力选择共用同一个 runId、conversationId 和 userId，
     * 但调用类型改为 PARAMETER_EXTRACTOR。
     */
    private ModelCallContext buildParameterContext(
            ModelCallContext source) {

        return ModelCallContext.builder().runId(
                        source == null ? null : source.getRunId())
                .conversationId( source == null
                                ? null
                                : source.getConversationId())
                .userId( source == null
                                ? null
                                : source.getUserId())
                .callType(ModelCallType.PARAMETER_EXTRACTOR)
                .callSequence(1)
                .build();
    }

    /**
     * 参数缺失或格式不合法时转为追问。
     *
     * 不能因为参数不完整就调用业务接口，
     * 也不应该把这类情况作为系统异常返回给用户。
     */
    private DynamicCapabilityPlan convertToClarify( DynamicCapabilityPlan plan,
            CapabilityDefinition capability,
            CapabilityInputValidationResult validationResult) {

        StringBuilder question = new StringBuilder();

        question.append("已确定你需要使用【")
                .append(capability.getCapabilityName())
                .append("】，但还需要补充或修正接口参数。");

        if (!validationResult.getMissingParameters() .isEmpty()) {

            question.append(" 缺少必填参数：")
                    .append( String.join("、", validationResult
                                            .getMissingParameters())
                    ).append("。");
        }

        if (!validationResult.getValidationErrors().isEmpty()) {

            question.append(" 参数格式问题：")
                    .append(String.join("；", validationResult.getValidationErrors()))
                    .append("。");
        }
        plan.setMatched(false);
        plan.setCapabilityCode(null);
        plan.setCapabilityName(null);
        plan.setInput(new LinkedHashMap<>());
        plan.setReason("能力已经确定，但接口参数未通过 JSON Schema 校验");
        plan.setClarifyQuestion(question.toString());
        return plan;
    }

    /**
     * 记录审计日志后返回规划结果。
     */
    private DynamicCapabilityPlan auditedReturn(
            ModelCallContext callContext,
            String userQuestion,
            List<CapabilityCandidate> candidates,
            DynamicCapabilityPlan plan,
            long planningStartTime) {

        routeAuditService.recordDecision(
                callContext,
                userQuestion,
                candidates,
                plan,
                System.currentTimeMillis() - planningStartTime);
        return plan;
    }
}