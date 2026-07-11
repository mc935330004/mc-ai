package org.example.ai.agent.plan;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.router.IntentResult;
import org.example.ai.agent.router.RouteType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 运行计划生成器。
 *
 * 业务查询不再写死 capabilityCode，而是从 ai_capability_definition 中动态选择能力。
 */
@Component
public class PlanTemplateRegistry {

    /**
     * 根据路由结果生成运行计划。
     */
    public RoutePlan buildPlan(String runId, AgentRequest request, IntentResult intentResult) {
        RouteType routeType = intentResult.getRouteType();

        if (routeType == RouteType.RAG_ONLY) {
            return buildRagOnlyPlan(runId, request);
        }
        if (routeType == RouteType.BUSINESS_QUERY) {
            return buildDynamicBusinessPlan( runId, request,routeType,intentResult);
        }
        if (routeType == RouteType.WORKFLOW_ACTION) {
            return buildWorkflowActionPlan(runId, request, intentResult);
        }
        if (routeType == RouteType.REJECT) {
            return buildRejectPlan(runId, request, intentResult);
        }
        return buildClarifyPlan(runId, request, intentResult);
    }

    /**
     * 纯知识库问答计划。
     */
    private RoutePlan buildRagOnlyPlan(String runId, AgentRequest request) {
        return RoutePlan.builder()
                .runId(runId)
                .routeType(RouteType.RAG_ONLY)
                .userQuestion(request.getUserQuestion())
                .goal("检索企业知识库文档，并基于文档内容回答用户问题")
                .steps(List.of(
                        PlanStep.builder()
                                .stepNo(1)
                                .stepType(StepType.RAG)
                                .stepName("检索企业知识库")
                                .ragQuery(request.getUserQuestion())
                                .outputKey("ragDocs")
                                .build(),
                        PlanStep.builder()
                                .stepNo(2)
                                .stepType(StepType.LLM_SUMMARY)
                                .stepName("基于知识库检索结果生成回答")
                                .inputKeys(List.of("ragDocs"))
                                .outputKey("finalAnswer")
                                .build()
                ))
                .build();
    }

    /**
     * 动态业务查询计划。
     *
     * 这里不再写死接口，而是让 DynamicCapabilityPlanner 根据用户问题选择已启用能力。
     */
    private RoutePlan buildDynamicBusinessPlan(String runId, AgentRequest request, RouteType routeType,
                                               IntentResult intentResult) {
        DynamicCapabilityPlan dynamicPlan =  intentResult.getDynamicCapabilityPlan();
        if (dynamicPlan == null || !dynamicPlan.isMatched()) {
            throw new IllegalStateException("业务路由缺少有效的动态能力计划");
        }
        return RoutePlan.builder()
                .runId(runId)
                .routeType(routeType)
                .userQuestion(request.getUserQuestion())
                .goal("调用业务系统真实接口查询数据，并根据字段字典生成 Markdown 回答")
                .steps(List.of(
                        PlanStep.builder()
                                .stepNo(1)
                                .stepType(StepType.BUSINESS_TOOL)
                                .stepName("调用动态业务能力：" + dynamicPlan.getCapabilityCode())
                                .capabilityCode(dynamicPlan.getCapabilityCode())
                                .input(dynamicPlan.getInput())
                                .outputKey("businessData")
                                .build(),
                        PlanStep.builder()
                                .stepNo(2)
                                .stepType(StepType.LLM_SUMMARY)
                                .stepName("根据业务数据和字段字典生成 Markdown 回答")
                                .inputKeys(List.of("businessData"))
                                .outputKey("finalAnswer")
                                .build()
                ))
                .build();
    }

    /**
     * 工作流动作第一版只提示，不自动执行。
     */
    private RoutePlan buildWorkflowActionPlan(String runId, AgentRequest request, IntentResult intentResult) {
        DynamicCapabilityPlan dynamicPlan =intentResult.getDynamicCapabilityPlan();
        if (dynamicPlan == null || !dynamicPlan.isMatched() || !"WRITE".equalsIgnoreCase(dynamicPlan.getSideEffect())) {
            throw new IllegalStateException("写操作路由缺少有效的 WRITE 能力计划");
        }
        return RoutePlan.builder()
                .runId(runId)
                .routeType(RouteType.WORKFLOW_ACTION)
                .userQuestion(request.getUserQuestion())
                .goal("识别到工作流动作，当前版本只生成提示，不自动执行")
                .steps(List.of(
                PlanStep.builder()
                        .stepNo(1)
                        .stepType(StepType.ACTION_PREVIEW)
                        .stepName("预览操作：" + dynamicPlan.getCapabilityName())
                        .capabilityCode(dynamicPlan.getCapabilityCode())
                        .input(dynamicPlan.getInput())
                        .outputKey("actionPreview")
                        .build()))
                .build();
    }

    /**
     * 拒绝危险操作。
     */
    private RoutePlan buildRejectPlan(String runId, AgentRequest request, IntentResult intentResult) {
        return RoutePlan.builder()
                .runId(runId)
                .routeType(RouteType.REJECT)
                .userQuestion(request.getUserQuestion())
                .goal("拒绝执行危险操作")
                .steps(List.of(
                        PlanStep.builder()
                                .stepNo(1)
                                .stepType(StepType.REJECT)
                                .stepName("拒绝执行")
                                .input(Map.of(
                                        "reason", intentResult.getReason(),
                                        "matchedKeywords", intentResult.getMatchedKeywords()
                                ))
                                .outputKey("rejectResult")
                                .build()
                ))
                .build();
    }

    /**
     * 信息不足时追问用户。
     */
    private RoutePlan buildClarifyPlan(String runId, AgentRequest request, IntentResult intentResult) {
        return RoutePlan.builder()
                .runId(runId)
                .routeType(RouteType.CLARIFY)
                .userQuestion(request.getUserQuestion())
                .goal("问题信息不足，需要追问用户")
                .steps(List.of(
                        PlanStep.builder()
                                .stepNo(1)
                                .stepType(StepType.CLARIFY)
                                .stepName("向用户追问信息")
                                .input(Map.of("clarifyQuestion", intentResult.getClarifyQuestion()))
                                .outputKey("clarifyResult")
                                .build()
                ))
                .build();
    }
}
