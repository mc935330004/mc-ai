package org.example.ai.agent.workflow.answer.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.model.UnifiedFactSet;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.workflow.answer.ResultArtifactDocumentAssembler;
import org.example.ai.agent.workflow.answer.ResultArtifactStatisticsService;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldContext;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldPolicy;
import org.example.ai.agent.workflow.answer.WorkflowAnswerModelPayload;
import org.example.ai.agent.workflow.answer.text.WorkflowTextFactBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 从上一轮安全结果快照准备追问事实。
 * 数学统计由Java计算，定性分析交给统一SSE回答服务。
 */
@Service
@RequiredArgsConstructor
public class ResultArtifactAnalysisService {

    private final ResultArtifactService artifactService;
    private final ResultArtifactStatisticsService statisticsService;
    private final ResultArtifactDocumentAssembler documentAssembler;
    private final WorkflowTextFactBuilder factBuilder;
    private final ObjectMapper objectMapper;

    public ResultArtifactAnalysisResult analyze(
            AgentRequest request,
            String runId) {

        // 保留原有用户、会话、过期时间和快照完整性校验。
        ResultArtifactSnapshot snapshot = artifactService.loadComplete(
                request.getUserId(),
                request.getConversationId(),
                request.getResultArtifactId()
        );

        // 统计请求继续沿用确定性计算及统计上下文保存。
        var statistics = statisticsService.tryAnalyze(request, runId, snapshot);
        if (statistics.isPresent()) {
            return statistics.get();
        }

        try {
            String semanticsJson = StringUtils.hasText(snapshot.fieldSemanticsJson())
                    ? snapshot.fieldSemanticsJson()
                    : "[]";

            List<WorkflowAnswerFieldContext> fields = objectMapper.readValue(
                    semanticsJson,
                    new TypeReference<List<WorkflowAnswerFieldContext>>() {}
            );

            WorkflowAnswerFieldPolicy policy =
                    new WorkflowAnswerFieldPolicy(fields);

            // 无损还原已保存的安全数据，不重新调用业务接口。
            WorkflowAnswerModelPayload payload = objectMapper.treeToValue(
                    documentAssembler.assemble(snapshot.chunkPlan()),
                    WorkflowAnswerModelPayload.class
            );

            // 只恢复同时允许用户查看、允许模型分析的字段。
            UnifiedFactSet factSet = factBuilder.buildSnapshot(
                    payload,
                    policy.modelFields(),
                    Boolean.TRUE.equals(snapshot.artifact().getDataComplete())
            );

            return new ResultArtifactAnalysisResult(
                    null,
                    "业务结果分析",
                    factSet.dataComplete(),
                    factSet,
                    true
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "上一轮结果快照结构解析失败",
                    exception
            );
        }
    }
}