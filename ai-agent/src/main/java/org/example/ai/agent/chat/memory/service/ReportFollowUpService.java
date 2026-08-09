package org.example.ai.agent.chat.memory.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.model.BusinessConversationState;
import org.example.ai.agent.chat.memory.model.PendingReportFollowUp;
import org.example.ai.agent.chat.memory.model.ReportFollowUpDecision;
import org.example.ai.agent.graph.GraphSpecParser;
import org.example.ai.agent.graph.model.GraphSpec;
import org.example.ai.agent.graph.model.report.ReportDefinitionSpec;
import org.example.ai.agent.graph.model.report.ReportFollowUpSpec;
import org.example.ai.agent.workflow.answer.ResultArtifactDocumentAssembler;
import org.example.ai.agent.workflow.answer.artifact.ResultArtifactService;
import org.example.ai.agent.workflow.answer.artifact.ResultArtifactSnapshot;
import org.example.ai.agent.workflow.answer.report.config.ReportValueReader;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * 处理报告完成后的通用业务追问。
 *
 * 本服务只负责：
 * 1. 创建待追问状态；
 * 2. 处理否定回答；
 * 3. 从 Artifact 中确定性匹配候选项；
 * 4. 生成目标执行参数。
 *
 * 本服务不直接调用业务能力或工作流。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportFollowUpService {

    private static final String SOURCE_PREFIX =
            "$source.";

    private static final String SELECTED_PREFIX =
            "$selected.";

    private static final int MAX_CLARIFY_OPTIONS = 5;

    private static final Set<String> CANCEL_ANSWERS =
            Set.of("不需要", "不需要了", "不用", "不用了", "不要", "否", "取消", "结束", "算了", "暂不需要");

    private static final Set<String> CONTINUE_ANSWERS =
            Set.of("需要", "要", "是", "可以", "继续", "好", "好的");

    private final ConversationStateService conversationStateService;
    private final WorkflowRuntimeSnapshotResolver snapshotResolver;
    private final GraphSpecParser graphSpecParser;
    private final ResultArtifactService artifactService;
    private final ResultArtifactDocumentAssembler documentAssembler;
    private final ReportValueReader valueReader;
    private final CapabilityDefinitionService capabilityService;
    private final ObjectMapper objectMapper;

    /**
     * 根据成功报告创建待追问状态。
     *
     * 创建失败不能影响已经生成的基础报告。
     */
    public Optional<PendingReportFollowUp> preparePending(
            AgentRequest request,
            WorkflowExecutionOutcome outcome,
            String artifactId) {

        if (request == null || outcome == null
                || !StringUtils.hasText(artifactId)
                || !StringUtils.hasText(outcome.workflowCode())
                || outcome.versionId() == null) {
            return Optional.empty();
        }

        try {
            /*
             * 没有配置追问属于正常情况，
             * 不能记录为追问准备失败。
             */
            ReportDefinitionSpec reportDefinition = loadReportDefinition(
                            outcome.workflowCode(),
                            outcome.versionId()
                    );

            ReportFollowUpSpec followUp =
                    reportDefinition.followUp();

            if (followUp == null || !followUp.enabled()) {
                return Optional.empty();
            }

            validateTarget(followUp);

            JsonNode resultNode = loadResultNode(request, artifactId, outcome.workflowCode(), outcome.versionId());

            Map<String, Object> inheritedInput =resolveKnownInput(
                            followUp,
                            resultNode
                    );

            PendingReportFollowUp pending =new PendingReportFollowUp();

            pending.setSourceReportType(
                    reportDefinition.reportType() == null
                            ? null
                            : reportDefinition
                            .reportType()
                            .name()
            );
            pending.setSourceWorkflowCode(outcome.workflowCode());

            pending.setSourceWorkflowVersionId(outcome.versionId());

            pending.setSourceArtifactId(artifactId);

            pending.setTargetType(followUp.targetType().trim().toUpperCase(Locale.ROOT));

            pending.setTargetCode(followUp.targetCode().trim());

            pending.setPrompt(followUp.prompt().trim());

            pending.setInheritedInput(inheritedInput);

            return Optional.of(pending);

        } catch (RuntimeException exception) {
            log.warn(
                    "准备报告追问状态失败，runId={}，workflowCode={}，errorType={}",
                    outcome.runId(),
                    outcome.workflowCode(),
                    exception.getClass().getSimpleName(),
                    exception
            );

            return Optional.empty();
        }
    }

    /**
     * 读取当前报告等待用户回答的追问提示。
     *
     * 只返回会话状态中的提示文本，
     * 不重新解析 Artifact，也不执行目标能力。
     */
    public Optional<String> findPendingPrompt(
            AgentRequest request) {

        if (request == null
                || !StringUtils.hasText(request.getUserId())
                || !StringUtils.hasText(request.getConversationId())) {
            return Optional.empty();
        }

        BusinessConversationState state =
                conversationStateService.loadState(
                        request.getUserId(),
                        request.getConversationId()
                ).orElse(null);

        if (state == null
                || state.getPendingReportFollowUp() == null) {
            return Optional.empty();
        }

        String prompt =
                state.getPendingReportFollowUp().getPrompt();

        if (!StringUtils.hasText(prompt)) {
            return Optional.empty();
        }

        return Optional.of(prompt.trim());
    }

    /**
     * 处理用户对待追问报告的回答。
     */
    public ReportFollowUpDecision resolve(AgentRequest request) {

        if (request == null || !StringUtils.hasText(request.getUserQuestion())) {
            return ReportFollowUpDecision.none();
        }

        BusinessConversationState state =
                conversationStateService.loadState(
                        request.getUserId(),
                        request.getConversationId()
                ).orElse(null);

        if (state == null || state.getPendingReportFollowUp() == null) {
            return ReportFollowUpDecision.none();
        }

        PendingReportFollowUp pending = state.getPendingReportFollowUp();

        String normalizedAnswer = normalizeAnswer(
                        request.getUserQuestion()
                );

        if (CANCEL_ANSWERS.contains(normalizedAnswer)) {
            clearPending(request, state);

            return ReportFollowUpDecision.cancelled(
                    "已结束本次明细查询。"
            );
        }

        if (CONTINUE_ANSWERS.contains(normalizedAnswer)) {
            return ReportFollowUpDecision.clarify(
                    "请直接输入需要查询的具体业务名称。"
            );
        }

        /*
         * “不是差旅费”不能被误识别成查询差旅费。
         */
        if (normalizedAnswer.startsWith("不是")) {
            return ReportFollowUpDecision.clarify(
                    "请直接输入需要查询的正确业务名称。"
            );
        }

        try {
            return resolveSelection(
                    request,
                    pending
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "解析报告追问失败，conversationId={}，sourceWorkflowCode={}，errorType={}",
                    request.getConversationId(),
                    pending.getSourceWorkflowCode(),
                    exception.getClass().getSimpleName(),
                    exception
            );

            return ReportFollowUpDecision.clarify(
                    "暂时无法读取上一份报告中的候选数据，"
                            + "请重新查询业务报告后再试。"
            );
        }
    }

    /**
     * 从上一轮 Artifact 中匹配用户输入。
     */
    private ReportFollowUpDecision resolveSelection(
            AgentRequest request,
            PendingReportFollowUp pending) {

        ReportFollowUpSpec followUp =
                loadFollowUpSpec(
                        pending.getSourceWorkflowCode(),
                        pending.getSourceWorkflowVersionId()
                );

        validatePendingTarget(
                pending,
                followUp
        );

        JsonNode resultNode = loadResultNode(
                request,
                pending.getSourceArtifactId(),
                pending.getSourceWorkflowCode(),
                pending.getSourceWorkflowVersionId()
        );

        List<Candidate> candidates =
                readCandidates(
                        resultNode,
                        followUp
                );

        List<Candidate> matched =
                matchCandidates(
                        request.getUserQuestion(),
                        candidates
                );

        if (matched.isEmpty()) {
            return ReportFollowUpDecision.clarify(
                    "没有在上一份报告中找到对应内容，"
                            + "请直接输入报告中显示的完整业务名称。"
            );
        }

        if (matched.size() > 1) {
            return ReportFollowUpDecision.clarify(
                    buildAmbiguousMessage(matched)
            );
        }

        Map<String, Object> targetInput =
                resolveSelectedInput(
                        followUp,
                        matched.get(0),
                        pending.getInheritedInput()
                );

        return ReportFollowUpDecision.ready(
                pending.getTargetType(),
                pending.getTargetCode(),
                targetInput
        );
    }

    /**
     * 读取来源工作流发布版本中的追问配置。
     */
    private ReportFollowUpSpec loadFollowUpSpec(
            String workflowCode,
            Long workflowVersionId) {

        ReportDefinitionSpec definition =
                loadReportDefinition(
                        workflowCode,
                        workflowVersionId
                );

        ReportFollowUpSpec followUp =
                definition.followUp();

        if (followUp == null || !followUp.enabled()) {
            throw new IllegalStateException(
                    "来源报告没有启用追问配置"
            );
        }

        return followUp;
    }

    /**
     * 读取来源工作流实际发布版本中的报告定义。
     */
    private ReportDefinitionSpec loadReportDefinition(
            String workflowCode,
            Long workflowVersionId) {

        PublishedWorkflow workflow =
                snapshotResolver.resolveExactVersion(
                        workflowCode,
                        workflowVersionId
                );

        GraphSpec graph = graphSpecParser.parse(
                workflow.version().getSnapshotJson()
        );

        if (graph.getReportDefinition() == null) {
            throw new IllegalStateException(
                    "来源工作流没有报告定义"
            );
        }

        return graph.getReportDefinition();
    }

    /**
     * 读取并还原当前用户和当前会话的安全 Artifact。
     */
    private JsonNode loadResultNode(
            AgentRequest request,
            String artifactId,
            String workflowCode,
            Long workflowVersionId) {

        ResultArtifactSnapshot snapshot =
                artifactService.loadComplete(
                        request.getUserId(),
                        request.getConversationId(),
                        artifactId
                );

        if (!Objects.equals(
                workflowCode,
                snapshot.artifact().getWorkflowCode())) {

            throw new IllegalStateException(
                    "追问来源工作流与结果快照不一致"
            );
        }

        if (!Objects.equals(
                workflowVersionId,
                snapshot.artifact().getWorkflowVersionId())) {

            throw new IllegalStateException(
                    "追问来源工作流版本与结果快照不一致"
            );
        }

        JsonNode payload =
                documentAssembler.assemble(
                        snapshot.chunkPlan()
                );

        JsonNode resultNode = payload.get("result");

        if (resultNode == null
                || resultNode.isNull()
                || resultNode.isMissingNode()) {

            throw new IllegalStateException(
                    "结果快照没有可追问的业务数据"
            );
        }

        return resultNode;
    }

    /**
     * 提前解析固定值和来源报告字段。
     *
     * $selected 参数等待用户选择后再解析。
     */
    private Map<String, Object> resolveKnownInput(
            ReportFollowUpSpec followUp,
            JsonNode resultNode) {

        Map<String, Object> result =new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry :followUp.inputMapping().entrySet()) {
            Object mappingValue = entry.getValue();
            if (isSelectedExpression(mappingValue)) {
                continue;
            }
            if (isSourceExpression(mappingValue)) {
                JsonNode value = valueReader.readScalar(
                        resultNode,
                        toSourcePath(String.valueOf(mappingValue))
                );

                if (value == null) {
                    throw new IllegalStateException("追问来源参数没有找到："+ entry.getKey());
                }
                result.put(entry.getKey(), toJavaValue(value));
                continue;
            }
            result.put(entry.getKey(),mappingValue);
        }

        return result;
    }

    /**
     * 从安全结果中读取候选业务行。
     */
    private List<Candidate> readCandidates(
            JsonNode resultNode,
            ReportFollowUpSpec followUp) {

        List<JsonNode> rows = valueReader.readMany(resultNode, followUp.optionRowPath());

        List<Candidate> result = new ArrayList<>();

        for (JsonNode row : rows) {
            JsonNode keyNode = valueReader.readScalar(
                    row,
                    followUp.optionKeyPath()
            );

            JsonNode labelNode = valueReader.readScalar(
                    row,
                    followUp.optionLabelPath()
            );

            if (keyNode == null || labelNode == null) {
                continue;
            }

            String key = keyNode.asText().trim();
            String label = labelNode.asText().trim();

            if (!StringUtils.hasText(key)
                    || !StringUtils.hasText(label)) {
                continue;
            }
            result.add(new Candidate(key, label, row));
        }

        return List.copyOf(result);
    }

    /**
     * 优先精确匹配名称或编码。
     *
     * 精确匹配失败后，
     * 只允许当前问题包含完整名称或完整编码。
     */
    private List<Candidate> matchCandidates(
            String question,
            List<Candidate> candidates) {

        String normalizedQuestion =
                normalizeAnswer(question);

        List<Candidate> exact =
                candidates.stream()
                        .filter(candidate ->normalizedQuestion.equals(normalizeAnswer(candidate.key()))
                                        || normalizedQuestion.equals(normalizeAnswer(candidate.label()))).toList();

        if (!exact.isEmpty()) {
            return exact;
        }

        return candidates.stream()
                .filter(candidate ->
                        normalizedQuestion.contains(
                                normalizeAnswer(
                                        candidate.key()
                                )
                        )
                                || normalizedQuestion.contains(
                                normalizeAnswer(
                                        candidate.label()
                                )
                        )
                )
                .toList();
    }

    /**
     * 补充用户唯一选择产生的目标参数。
     */
    private Map<String, Object> resolveSelectedInput(
            ReportFollowUpSpec followUp,
            Candidate candidate,
            Map<String, Object> inheritedInput) {

        Map<String, Object> result =
                inheritedInput == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(
                        inheritedInput
                );

        for (Map.Entry<String, Object> entry :
                followUp.inputMapping().entrySet()) {

            if (!isSelectedExpression(
                    entry.getValue())) {
                continue;
            }

            JsonNode value = valueReader.readScalar(
                    candidate.row(),
                    toSelectedPath(
                            String.valueOf(
                                    entry.getValue()
                            )
                    )
            );

            if (value == null) {
                throw new IllegalStateException(
                        "用户选择结果缺少目标参数："
                                + entry.getKey()
                );
            }

            result.put(
                    entry.getKey(),
                    toJavaValue(value)
            );
        }

        return result;
    }

    /**
     * 目标能力必须存在、已发布、已启用且为只读能力。
     */
    private void validateTarget(
            ReportFollowUpSpec followUp) {

        if ("CAPABILITY".equalsIgnoreCase(
                followUp.targetType())) {

            CapabilityDefinition capability =
                    capabilityService.getEnabledByCode(
                            followUp.targetCode()
                    );

            if (capability == null) {
                throw new IllegalStateException(
                        "追问目标能力不存在或未启用"
                );
            }

            if (!"READ".equalsIgnoreCase(
                    capability.getSideEffect())) {

                throw new IllegalStateException(
                        "报告追问只能直接执行只读能力"
                );
            }

            return;
        }

        if ("WORKFLOW".equalsIgnoreCase(
                followUp.targetType())) {

            snapshotResolver.resolveByCode(
                    followUp.targetCode()
            );

            return;
        }

        throw new IllegalStateException(
                "不支持的报告追问目标类型"
        );
    }

    /**
     * 防止会话状态和来源发布快照中的目标被异常替换。
     */
    private void validatePendingTarget(
            PendingReportFollowUp pending,
            ReportFollowUpSpec followUp) {

        if (!Objects.equals(
                pending.getTargetCode(),
                followUp.targetCode())) {

            throw new IllegalStateException(
                    "追问目标编码与发布配置不一致"
            );
        }

        if (!StringUtils.hasText(pending.getTargetType())
                || !StringUtils.hasText(followUp.targetType())
                || !pending.getTargetType().equalsIgnoreCase(
                followUp.targetType())) {

            throw new IllegalStateException(
                    "追问目标类型与发布配置不一致"
            );
        }

        validateTarget(followUp);
    }

    /**
     * 清除待追问状态，但保留上一轮 Artifact。
     */
    private void clearPending(
            AgentRequest request,
            BusinessConversationState state) {

        state.setPendingReportFollowUp(null);

        conversationStateService.saveState(
                request.getUserId(),
                request.getConversationId(),
                state
        );
    }

    /**
     * 多个候选匹配时返回有限的澄清列表。
     */
    private String buildAmbiguousMessage(
            List<Candidate> candidates) {

        LinkedHashSet<String> labels =
                new LinkedHashSet<>();

        for (Candidate candidate : candidates) {
            labels.add(candidate.label());

            if (labels.size()
                    >= MAX_CLARIFY_OPTIONS) {
                break;
            }
        }

        return "匹配到多个业务项："
                + String.join("、", labels)
                + "。请直接输入其中一个完整名称。";
    }

    private boolean isSourceExpression(
            Object value) {

        return value instanceof String text
                && text.trim().startsWith(
                SOURCE_PREFIX
        );
    }

    private boolean isSelectedExpression(
            Object value) {

        return value instanceof String text
                && text.trim().startsWith(
                SELECTED_PREFIX
        );
    }

    private String toSourcePath(
            String expression) {

        return "$."
                + expression.trim().substring(
                SOURCE_PREFIX.length()
        );
    }

    private String toSelectedPath(
            String expression) {

        return expression.trim().substring(
                SELECTED_PREFIX.length()
        );
    }

    private Object toJavaValue(JsonNode value) {

        return objectMapper.convertValue(
                value,
                Object.class
        );
    }

    /**
     * 只移除空白和常见标点，
     * 不进行拼音或编辑距离模糊匹配。
     */
    private String normalizeAnswer(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll(
                        "[\\s，,。.!！?？；;：:]+",
                        ""
                );
    }

    /**
     * 保存一个可安全参与匹配的候选业务行。
     */
    private record Candidate(
            String key,
            String label,
            JsonNode row) {
    }
}