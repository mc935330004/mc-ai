package org.example.ai.agent.router;

import cn.hutool.core.collection.CollectionUtil;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.enums.WorkflowPlanStatus;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.plan.DynamicCapabilityPlan;
import org.example.ai.agent.plan.DynamicCapabilityPlanner;
import org.example.ai.agent.workflow.plan.WorkflowPlan;
import org.example.ai.agent.workflow.plan.WorkflowPlanner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.example.ai.agent.common.exception.BusinessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于规则的意图路由器。
 *
 * 第一版先不要依赖大模型判断路由，
 * 因为业务系统 Agent 最重要的是稳定、可控、可复盘。
 *
 * 判断顺序建议：
 * 1. 先判断危险操作
 * 2. 再判断写操作 / 工作流动作
 * 3. 再判断统计分析
 * 4. 再判断业务查询、RAG 查询、混合查询
 * 5. 最后兜底追问用户
 */
@Component
@RequiredArgsConstructor
public class RuleBasedIntentRouter implements IntentRouter {

    /**
     * 根据数据库中的已启用能力匹配用户问题。
     */
    private final DynamicCapabilityPlanner dynamicCapabilityPlanner;
    private final WorkflowPlanner workflowPlanner;
    /**
     * 业务数据类关键词。
     *
     * 命中这些词，通常说明用户想查项目、合同、回款、任务等真实业务数据。
     */
    private static final List<String> BUSINESS_KEYWORDS = List.of(
            "项目", "合同", "金额", "回款", "付款", "客户", "任务", "审批", "编号", "进度","产值"
    );

    /**
     * 企业知识库 / 文档类关键词。
     *
     * 命中这些词，通常说明用户想查制度、流程、规范、说明文档。
     */
    private static final List<String> RAG_KEYWORDS = List.of(
            "制度", "流程", "规范", "文档", "说明", "规则", "怎么操作", "如何", "要求"
    );

    /**
     * 分析类关键词。
     *
     * 命中这些词，通常说明用户不只是要查数据，还要解释原因、风险或建议。
     */
    private static final List<String> ANALYSIS_KEYWORDS = List.of(
             "原因", "建议", "分析", "对比", "是否异常", "有没有问题"
    );

    /**
     * 统计类关键词。
     *
     * 命中这些词，通常说明用户需要统计、汇总、排名、趋势等结构化结果。
     */
    private static final List<String> STATISTIC_KEYWORDS = List.of(
            "统计", "汇总", "排名", "趋势", "占比", "本月", "本季度", "今年"
    );

    /**
     * 写操作 / 工作流动作关键词。
     *
     * 第一版只识别，不自动执行。
     * 后续必须接入人工确认机制。
     */
    private static final List<String> ACTION_KEYWORDS = List.of(
            "发起", "提交", "修改", "新增", "删除", "作废", "审批通过", "驳回"
    );

    /**
     * 高危操作关键词。
     *
     * 命中这些词时，第一版直接拒绝。
     */
    private static final List<String> DANGEROUS_KEYWORDS = List.of(
            "删除全部", "清空", "批量删除", "作废所有", "覆盖所有"
    );

    /**
     * 执行路由判断。
     *
     * @param request Agent 请求
     * @return 路由结果
     */
    @Override
    public IntentResult route(AgentRequest request, String runId) {
        String question = normalizeQuestion(request);
        ModelCallContext plannerContext = ModelCallContext.builder()
                .runId(runId)
                .conversationId(request == null ? null : request.getConversationId())
                .userId(request == null ? null : request.getUserId())
                .callType(ModelCallType.PLANNER)
                .callSequence(1)
                .build();
        Map<String, Object> actionForm =readActionForm(request);
        if (actionForm != null) {
            WorkflowPlan workflowPlan =
                    workflowPlanner.planActionForm(
                            actionForm,
                            plannerContext,
                            request.getAuthorization()
                    );

            if (workflowPlan.isReady()) {
                return buildWorkflowActionResult(
                        workflowPlan,
                        List.of("WRITE表单提交"));
            }

            return buildWorkflowActionFormResult(
                    workflowPlan,
                    List.of("WRITE表单提交"));
        }
        // 用户问题为空时，不继续执行任何链路，直接要求用户补充问题。
        if (!StringUtils.hasText(question)) {
            return clarify("请先输入你要咨询的问题。");
        }

        // 1. 优先判断危险操作，避免 Agent 自动执行不可逆动作。
        List<String> dangerousHits = matchKeywords(question, DANGEROUS_KEYWORDS);
        if (CollectionUtil.isNotEmpty(dangerousHits)) {
            return IntentResult.builder()
                    .routeType(RouteType.REJECT)
                    .confidence(1.0)
                    .reason("命中高风险操作关键词，第一版 Agent 不允许自动执行危险操作")
                    .matchedKeywords(dangerousHits)
                    .needClarify(false)
                    .entities(Map.of())
                    .build();
        }

        // 2. 判断是否为写操作或工作流动作。
        List<String> actionHits = matchKeywords(question, ACTION_KEYWORDS);
        if (!actionHits.isEmpty()) {
            return routeAction(question, actionHits, plannerContext);
        }

        // 3. 判断统计分析类问题。
        List<String> statisticHits = matchKeywords(question, STATISTIC_KEYWORDS);
        if (!statisticHits.isEmpty()) {
            return routeBusiness(
                    question,
                    RouteType.STATISTIC_QUERY,
                    statisticHits,
                    "用户问题包含统计、汇总、排名或趋势意图",
                    0.8,plannerContext
            );
        }

        // 4. 分别匹配业务关键词、知识库关键词、分析关键词。
        List<String> businessHits = matchKeywords(question, BUSINESS_KEYWORDS);
        List<String> ragHits = matchKeywords(question, RAG_KEYWORDS);
        List<String> analysisHits = matchKeywords(question, ANALYSIS_KEYWORDS);

        // 5. 同时命中业务数据和文档/分析关键词，判断为混合问答。
        if (!businessHits.isEmpty() && (!ragHits.isEmpty() || !analysisHits.isEmpty())) {
            return routeBusiness(
                    question,
                    RouteType.MIXED_QUERY,
                    mergeKeywords(businessHits, ragHits, analysisHits),
                    "用户问题同时包含业务数据和分析意图",
                    0.85,plannerContext
            );
        }

        // 6. 只命中业务关键词，判断为业务数据查询。
        if (!businessHits.isEmpty()) {
            return routeBusiness(
                    question,
                    RouteType.BUSINESS_QUERY,
                    businessHits,
                    "用户问题属于业务数据查询",
                    0.75,plannerContext
            );
        }

        // 7. 只命中文档或分析关键词，先走 RAG。
        // 注意：纯“风险制度是什么”这类问题可以走 RAG_ONLY。
        if (!ragHits.isEmpty() || !analysisHits.isEmpty()) {
            return IntentResult.builder()
                    .routeType(RouteType.RAG_ONLY)
                    .confidence(0.75)
                    .reason("问题主要是制度、流程、规范、文档说明类知识问答")
                    .matchedKeywords(mergeKeywords(ragHits, analysisHits))
                    .needClarify(false)
                    .entities(Map.of())
                    .build();
        }

        // 固定关键词没有命中时，再从数据库能力目录中动态匹配。
        // 这样新增里程碑、风险、成本等能力后不需要修改 Java 关键词。
        return routeBusiness(
                question,
                RouteType.BUSINESS_QUERY,
                List.of(),
                "未命中固定关键词，尝试从已启用能力目录动态匹配",
                0.65,plannerContext
        );
    }

    /**
     * 标准化用户问题。
     *
     * 避免 request 为空或 userQuestion 为空导致空指针异常。
     */
    private String normalizeQuestion(AgentRequest request) {
        if (request == null || request.getUserQuestion() == null) {
            return "";
        }
        return request.getUserQuestion().trim();
    }

    /**
     * 匹配问题中命中的关键词。
     *
     * 第一版先使用 contains 简单匹配。
     * 后续可以升级为分词、同义词词典、业务实体识别。
     */
    private List<String> matchKeywords(String question, List<String> keywords) {
        return keywords.stream()
                .filter(question::contains)
                .toList();
    }

    /**
     * 合并多组关键词，并去重。
     */
    @SafeVarargs
    private final List<String> mergeKeywords(List<String>... keywordLists) {
        List<String> result = new ArrayList<>();
        for (List<String> keywordList : keywordLists) {
            result.addAll(keywordList);
        }
        return result.stream().distinct().toList();
    }

    /**
     * 构造追问结果。
     *
     * 当路由不明确时，不要贸然调用业务接口。
     */
    private IntentResult clarify(String question) {
        return IntentResult.builder()
                .routeType(RouteType.CLARIFY)
                .confidence(0.4)
                .reason("问题信息不足，无法稳定判断路由")
                .needClarify(true)
                .clarifyQuestion(question)
                .matchedKeywords(List.of())
                .entities(Map.of())
                .build();
    }

    /**
     * 根据已启用能力目录判断问题是否属于业务查询。
     */
    private IntentResult routeBusiness(String question, RouteType routeType,List<String> matchedKeywords,String routeReason,
            double confidence,ModelCallContext plannerContext) {
        WorkflowPlan workflowPlan =workflowPlanner.plan(question,plannerContext);

        if (workflowPlan.getStatus()== WorkflowPlanStatus.NEED_CLARIFY) {
            return IntentResult.builder()
                    .routeType(RouteType.CLARIFY)
                    .confidence(
                            workflowPlan.getConfidence()
                    )
                    .reason(workflowPlan.getReason())
                    .needClarify(true)
                    .clarifyQuestion(
                            workflowPlan
                                    .getClarifyQuestion()
                    )
                    .matchedKeywords(
                            matchedKeywords
                    )
                    .entities(Map.of())
                    .workflowPlan(workflowPlan)
                    .build();
        }
        /*
         * 防止用户没有明显动作关键词时，
         * WRITE 工作流被当成查询工作流直接执行。
         */
        if (workflowPlan.isReady() && workflowPlan.isWriteAction()) {
            return buildWorkflowActionResult(
                    workflowPlan,
                    matchedKeywords
            );
        }

        if (workflowPlan.isReady()) {
            return IntentResult.builder()
                    .routeType(
                            RouteType.WORKFLOW_QUERY
                    )
                    .confidence(
                            workflowPlan.getConfidence()
                    )
                    .reason(
                            routeReason + "；命中已发布工作流："
                                    + workflowPlan
                                    .getWorkflowName()
                    )
                    .needClarify(false)
                    .matchedKeywords(
                            matchedKeywords
                    )
                    .entities(Map.of())
                    .workflowPlan(workflowPlan)
                    .build();
        }
        DynamicCapabilityPlan dynamicPlan =dynamicCapabilityPlanner.plan(question, plannerContext);
        // 没有匹配能力时不能调用业务接口，转为追问用户
        if (!dynamicPlan.isMatched()) {
            String clarifyQuestion = StringUtils.hasText(dynamicPlan.getClarifyQuestion()) ? dynamicPlan.getClarifyQuestion()
                            : "当前没有找到匹配的业务能力，请补充具体业务对象和操作目标。";
            return clarify(clarifyQuestion);
        }
        String sideEffect = dynamicPlan.getSideEffect();
        // 危险能力始终拒绝，不能进入工具执行器
        if ("DANGEROUS".equalsIgnoreCase(sideEffect)) {
            return IntentResult.builder()
                    .routeType(RouteType.REJECT)
                    .confidence(1.0)
                    .reason("匹配到危险业务能力，禁止自动执行")
                    .matchedKeywords(matchedKeywords)
                    .needClarify(false)
                    .entities(Map.of())
                    .dynamicCapabilityPlan(dynamicPlan)
                    .build();
        }
        // 只要数据库配置为 WRITE，就必须进入操作预览
        // 不能因为用户没有命中动作关键词而当成普通查询执行
        if ("WRITE".equalsIgnoreCase(sideEffect)) {
            return IntentResult.builder()
                    .routeType(RouteType.WORKFLOW_ACTION)
                    .confidence(0.9)
                    .reason("匹配到 WRITE 能力，必须先展示操作预览")
                    .matchedKeywords(matchedKeywords)
                    .needClarify(false)
                    .entities(Map.of())
                    .dynamicCapabilityPlan(dynamicPlan)
                    .build();
        }
        // READ 能力才允许进入正常工具执行链路
        return IntentResult.builder()
                .routeType(routeType)
                .confidence(confidence)
                .reason(routeReason + "；" + dynamicPlan.getReason())
                .matchedKeywords(matchedKeywords)
                .needClarify(false)
                .entities(Map.of())
                .dynamicCapabilityPlan(dynamicPlan)
                .build();
    }
    /**
     * 根据能力目录匹配写操作能力。
     */
    private IntentResult routeAction(String question, List<String> actionHits,ModelCallContext plannerContext) {
        /*
         * 写操作优先匹配已经发布的 GraphSpec 工作流。
         * 没匹配到时，继续兼容原来的动态 WRITE 能力。
         */
        WorkflowPlan workflowPlan = workflowPlanner.plan(question,plannerContext);

        if (workflowPlan.isReady() && workflowPlan.isWriteAction()) {
            return buildWorkflowActionResult(
                    workflowPlan,
                    actionHits
            );
        }
        if (workflowPlan.getStatus()== WorkflowPlanStatus.NEED_CLARIFY && workflowPlan.isWriteAction()) {
            return buildWorkflowActionFormResult(
                    workflowPlan,
                    actionHits );
        }

        DynamicCapabilityPlan dynamicPlan = dynamicCapabilityPlanner.plan(question, plannerContext);
        if (!dynamicPlan.isMatched()) {
            return clarify(dynamicPlan.getClarifyQuestion());
        }
        // 危险能力无论是否需要确认，都不允许进入执行流程
        if ("DANGEROUS".equalsIgnoreCase(dynamicPlan.getSideEffect())) {
            return IntentResult.builder()
                    .routeType(RouteType.REJECT)
                    .confidence(1.0)
                    .reason("匹配到危险业务能力，禁止自动执行")
                    .matchedKeywords(actionHits)
                    .needClarify(false)
                    .entities(Map.of())
                    .dynamicCapabilityPlan(dynamicPlan)
                    .build();
        }
        // 动作意图必须匹配 WRITE 能力，不能拿 READ 能力冒充写操作
        if (!"WRITE".equalsIgnoreCase(dynamicPlan.getSideEffect())) {
            return clarify("没有找到与当前操作匹配的写入能力，请补充具体操作目标。");
        }
        return IntentResult.builder()
                .routeType(RouteType.WORKFLOW_ACTION)
                .confidence(0.9)
                .reason("已匹配写操作能力，等待用户确认")
                .matchedKeywords(actionHits)
                .needClarify(false)
                .entities(Map.of())
                .dynamicCapabilityPlan(dynamicPlan)
                .build();
    }
    /**
     * 将工作流 WRITE 计划转换成现有动态能力计划。
     *
     * 后续继续复用现有 PendingAction，
     * 不创建第二套确认执行逻辑。
     */
    private IntentResult buildWorkflowActionResult(
            WorkflowPlan workflowPlan,
            List<String> matchedKeywords) {

        DynamicCapabilityPlan actionPlan =
                new DynamicCapabilityPlan();

        actionPlan.setMatched(true);
        actionPlan.setCapabilityCode(
                workflowPlan
                        .getActionCapabilityCode()
        );
        actionPlan.setCapabilityName(
                workflowPlan
                        .getActionCapabilityName()
        );
        actionPlan.setInput(new LinkedHashMap<>( workflowPlan.getActionInput()));
        actionPlan.setDisplayInput(new LinkedHashMap<>(workflowPlan.getActionDisplayInput()));
        actionPlan.setSideEffect("WRITE");
        actionPlan.setRequireConfirm(true);
        actionPlan.setConfidence(
                workflowPlan.getConfidence()
        );
        actionPlan.setReason(
                "命中已发布WRITE工作流："
                        + workflowPlan
                        .getWorkflowName()
        );

        return IntentResult.builder()
                .routeType(
                        RouteType.WORKFLOW_ACTION
                )
                .confidence(
                        workflowPlan.getConfidence()
                )
                .reason(actionPlan.getReason())
                .needClarify(false)
                .matchedKeywords(matchedKeywords)
                .entities(Map.of())
                .workflowPlan(workflowPlan)
                .dynamicCapabilityPlan(actionPlan)
                .build();
    }

    /**
     * 保留WRITE工作流上下文，让编排器发送ACTION_FORM。
     */
    private IntentResult buildWorkflowActionFormResult(
            WorkflowPlan workflowPlan,
            List<String> matchedKeywords) {

        return IntentResult.builder()
                .routeType(RouteType.CLARIFY)
                .confidence(
                        workflowPlan.getConfidence()
                )
                .reason(
                        workflowPlan.getReason()
                )
                .needClarify(true)
                .clarifyQuestion(
                        workflowPlan
                                .getClarifyQuestion()
                )
                .matchedKeywords(
                        matchedKeywords
                )
                .entities(Map.of())
                .workflowPlan(workflowPlan)
                .build();
    }

    /**
     * 从AgentRequest.extra中读取结构化WRITE表单。
     */
    private Map<String, Object> readActionForm(
            AgentRequest request) {

        if (request == null|| request.getExtra() == null|| !request.getExtra().containsKey("actionForm")) {
            return null;
        }

        Object value =request.getExtra().get("actionForm");

        if (!(value instanceof Map<?, ?> source)) {
            throw new BusinessException(
                    400,
                    "extra.actionForm必须是JSON对象"
            );
        }
        Map<String, Object> result =new LinkedHashMap<>();

        source.forEach((key, child) ->
                result.put(String.valueOf(key),
                        child) );
        return result;
    }
}