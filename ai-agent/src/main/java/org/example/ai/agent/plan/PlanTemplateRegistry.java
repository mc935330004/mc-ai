package org.example.ai.agent.plan;

import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.router.IntentResult;
import org.example.ai.agent.router.RouteType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 计划模板注册器。
 *
 * 第一版不做复杂的自主规划，
 * 直接根据 routeType 生成固定模板。
 *
 * 好处：
 * 1. 稳定
 * 2. 可控
 * 3. 容易调试
 * 4. 后续可以逐步替换成数据库配置模板
 */
@Component
public class PlanTemplateRegistry {

    /**
     * 根据路由结果生成运行计划。
     *
     * @param runId        本次 Agent 运行 ID
     * @param request      用户请求
     * @param intentResult 路由结果
     * @return 运行计划
     */
    public RoutePlan buildPlan(String runId, AgentRequest request, IntentResult intentResult) {
        RouteType routeType = intentResult.getRouteType();

        if (routeType == RouteType.RAG_ONLY) {
            return buildRagOnlyPlan(runId, request);
        }

        if (routeType == RouteType.BUSINESS_QUERY) {
            return buildBusinessQueryPlan(runId, request);
        }

        if (routeType == RouteType.MIXED_QUERY) {
            return buildMixedQueryPlan(runId, request);
        }

        if (routeType == RouteType.STATISTIC_QUERY) {
            return buildStatisticQueryPlan(runId, request);
        }

        if (routeType == RouteType.WORKFLOW_ACTION) {
            return buildWorkflowActionPlan(runId, request);
        }

        if (routeType == RouteType.REJECT) {
            return buildRejectPlan(runId, request, intentResult);
        }

        return buildClarifyPlan(runId, request, intentResult);
    }

    /**
     * 构建纯 RAG 问答计划。
     *
     * 示例：
     * 用户问：“合同审批流程是什么？”
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
     * 构建业务查询计划。
     *
     * 示例：
     * 用户问：“查询 A 项目的合同金额”
     *
     * 第一版先给出固定步骤：
     * 1. 查项目
     * 2. 查合同
     * 3. 汇总回答
     */
    private RoutePlan buildBusinessQueryPlan(String runId, AgentRequest request) {
        String projectName = extractProjectName(request.getUserQuestion());

        return RoutePlan.builder()
                .runId(runId)
                .routeType(RouteType.BUSINESS_QUERY)
                .userQuestion(request.getUserQuestion())
                .goal("查询项目相关业务数据，并生成结构化回答")
                .steps(List.of(
                        PlanStep.builder()
                                .stepNo(1)
                                .stepType(StepType.BUSINESS_TOOL)
                                .stepName("根据项目编码查询工程类产值")
                                .capabilityCode("pm.project.outputMain")
                                .input(Map.of("queryStr", projectName))
                                .outputKey("outputValue")
                                .build(),
                        PlanStep.builder()
                                .stepNo(2)
                                .stepType(StepType.BUSINESS_TOOL)
                                .stepName("根据项目 ID 查询合同列表")
                                .capabilityCode("pm.contract.listByProjectId")
                                .inputRef(Map.of("projectId", "$.project.id"))
                                .outputKey("contracts")
                                .build(),
                        PlanStep.builder()
                                .stepNo(3)
                                .stepType(StepType.LLM_SUMMARY)
                                .stepName("汇总业务数据生成回答")
                                .inputKeys(List.of("project", "contracts"))
                                .outputKey("finalAnswer")
                                .build()
                ))
                .build();
    }

    /**
     * 构建混合问答计划。
     *
     * 示例：
     * 用户问：“查询 A 项目回款情况，并分析有没有风险”
     *
     * 混合问答一般包含：
     * 1. 查业务数据
     * 2. 检索制度文档
     * 3. 结合两类信息生成回答
     */
    private RoutePlan buildMixedQueryPlan(String runId, AgentRequest request) {
        String projectName = extractProjectName(request.getUserQuestion());

        return RoutePlan.builder()
                .runId(runId)
                .routeType(RouteType.MIXED_QUERY)
                .userQuestion(request.getUserQuestion())
                .goal("查询业务数据，并结合企业制度文档进行解释和风险分析")
                .steps(List.of(
                        PlanStep.builder()
                                .stepNo(1)
                                .stepType(StepType.BUSINESS_TOOL)
                                .stepName("根据项目名称查询工程类产值")
                                .capabilityCode("pm.project.outputMain")
                                .input(Map.of("queryStr", projectName))
                                .outputKey("outputValue")
                                .build(),
                        PlanStep.builder()
                                .stepNo(2)
                                .stepType(StepType.BUSINESS_TOOL)
                                .stepName("根据项目 ID 查询合同列表")
                                .capabilityCode("pm.contract.listByProjectId")
                                .inputRef(Map.of("projectId", "$.project.id"))
                                .outputKey("contracts")
                                .build(),
                        PlanStep.builder()
                                .stepNo(3)
                                .stepType(StepType.BUSINESS_TOOL)
                                .stepName("根据项目 ID 查询回款汇总")
                                .capabilityCode("pm.payment.summaryByProjectId")
                                .inputRef(Map.of("projectId", "$.project.id"))
                                .outputKey("paymentSummary")
                                .build(),
                        PlanStep.builder()
                                .stepNo(4)
                                .stepType(StepType.RAG)
                                .stepName("检索项目回款风险相关制度")
                                .ragQuery("项目回款风险 合同回款制度 风险判断规则")
                                .outputKey("riskDocs")
                                .build(),
                        PlanStep.builder()
                                .stepNo(5)
                                .stepType(StepType.LLM_SUMMARY)
                                .stepName("结合业务数据和制度依据生成最终回答")
                                .inputKeys(List.of("project", "contracts", "paymentSummary", "riskDocs"))
                                .outputKey("finalAnswer")
                                .build()
                ))
                .build();
    }

    /**
     * 构建统计分析计划。
     *
     * 示例：
     * 用户问：“本月合同金额按项目类型统计”
     */
    private RoutePlan buildStatisticQueryPlan(String runId, AgentRequest request) {
        return RoutePlan.builder()
                .runId(runId)
                .routeType(RouteType.STATISTIC_QUERY)
                .userQuestion(request.getUserQuestion())
                .goal("调用统计类业务能力，生成统计结果和文字解释")
                .steps(List.of(
                        PlanStep.builder()
                                .stepNo(1)
                                .stepType(StepType.BUSINESS_TOOL)
                                .stepName("调用合同统计能力")
                                .capabilityCode("pm.contract.statistic")
                                .input(Map.of("question", request.getUserQuestion()))
                                .outputKey("statisticResult")
                                .build(),
                        PlanStep.builder()
                                .stepNo(2)
                                .stepType(StepType.LLM_SUMMARY)
                                .stepName("解释统计结果")
                                .inputKeys(List.of("statisticResult"))
                                .outputKey("finalAnswer")
                                .build()
                ))
                .build();
    }

    /**
     * 构建工作流动作计划。
     *
     * 第一版不执行，只生成一个需要人工确认的计划。
     */
    private RoutePlan buildWorkflowActionPlan(String runId, AgentRequest request) {
        return RoutePlan.builder()
                .runId(runId)
                .routeType(RouteType.WORKFLOW_ACTION)
                .userQuestion(request.getUserQuestion())
                .goal("识别到工作流动作，当前版本只生成计划，不自动执行")
                .steps(List.of(
                        PlanStep.builder()
                                .stepNo(1)
                                .stepType(StepType.CLARIFY)
                                .stepName("提示用户当前版本暂不支持自动执行工作流动作")
                                .input(Map.of("question", request.getUserQuestion()))
                                .outputKey("workflowActionNotice")
                                .build()
                ))
                .build();
    }

    /**
     * 构建拒绝执行计划。
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
     * 构建追问计划。
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

    /**
     * 从用户问题中简单提取项目名称。
     *
     * 第一版先做非常轻量的规则：
     * - 如果包含“项目”，截取“项目”前面的短文本作为项目名候选
     * - 如果无法识别，就先把原始问题放进去
     *
     * 后续可以升级为：
     * 1. 正则提取
     * 2. 项目名称词典匹配
     * 3. 调用 LLM 做实体抽取
     */
    private String extractProjectName(String question) {
        if (!StringUtils.hasText(question)) {
            return "";
        }

        int index = question.indexOf("项目");
        if (index <= 0) {
            return question;
        }

        int start = Math.max(0, index - 20);
        return question.substring(start, index + 2).trim();
    }
}