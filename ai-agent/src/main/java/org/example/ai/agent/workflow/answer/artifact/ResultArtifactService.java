package org.example.ai.agent.workflow.answer.artifact;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.workflow.answer.WorkflowAnswerFieldPolicy;
import org.example.ai.agent.workflow.answer.WorkflowResultTraceData;
import org.example.ai.agent.workflow.answer.artifact.entity.ResultArtifact;
import org.example.ai.agent.workflow.answer.artifact.entity.ResultArtifactChunk;
import org.example.ai.agent.workflow.answer.artifact.mapper.ResultArtifactChunkMapper;
import org.example.ai.agent.workflow.answer.artifact.mapper.ResultArtifactMapper;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunk;
import org.example.ai.agent.workflow.answer.chunk.WorkflowAnswerChunkPlan;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;

import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 保存工作流安全结果快照。
 *
 * 直接复用现有 WorkflowAnswerChunkPlan：
 * 1. 不再重复实现一套分块逻辑；
 * 2. 保存的数据与发送给模型的数据保持一致；
 * 3. 任意分块写入失败，整个事务回滚；
 * 4. 只有全部分块写入成功，状态才更新为 COMPLETE。
 */
@Service
public class ResultArtifactService {

    private final ResultArtifactMapper artifactMapper;
    private final ResultArtifactChunkMapper chunkMapper;
    private final ObjectMapper objectMapper;
    private final long retentionHours;

    public ResultArtifactService(
            ResultArtifactMapper artifactMapper,
            ResultArtifactChunkMapper chunkMapper,
            ObjectMapper objectMapper,
            @Value("${ai.workflow.answer.artifact.retention-hours:24}")
            long retentionHours) {

        if (retentionHours <= 0) {
            throw new IllegalArgumentException(
                    "结果快照保留时间必须大于0小时"
            );
        }

        this.artifactMapper = artifactMapper;
        this.chunkMapper = chunkMapper;
        this.objectMapper = objectMapper;
        this.retentionHours = retentionHours;
    }

    /**
     * 保存安全结果及完整性账本。
     *
     * 返回 artifactId，后续写入会话业务状态，
     * 供“汇总刚才的数据”等追问直接定位。
     */
    @Transactional(rollbackFor = Exception.class)
    public String save(
            AgentRequest request,
            WorkflowExecutionOutcome outcome,
            WorkflowAnswerFieldPolicy fieldPolicy,
            WorkflowAnswerChunkPlan chunkPlan) {

        validate(request, outcome, chunkPlan);

        String artifactId = UUID.randomUUID().toString().replace("-", "");

        LocalDateTime now = LocalDateTime.now();

        WorkflowResultTraceData traceData =
                WorkflowResultTraceData.from(
                        outcome,
                        "REPORT"
                );

        ResultArtifact artifact = new ResultArtifact();
        artifact.setId(artifactId);
        artifact.setRunId(outcome.runId());
        artifact.setSessionId(request.getConversationId());
        artifact.setUserId(request.getUserId());

        artifact.setWorkflowCode(outcome.workflowCode());
        artifact.setWorkflowName(outcome.workflowName());
        artifact.setWorkflowVersionId(outcome.versionId());

        artifact.setStatus("WRITING");
        artifact.setPartialSuccess(outcome.partialSuccess());
        artifact.setDataComplete(traceData.workflowDataComplete());

        artifact.setTopLevelTotalCount(traceData.topLevelTotalCount());
        artifact.setTopLevelSuccessCount(
                traceData.topLevelSuccessCount()
        );
        artifact.setTopLevelFailureCount(
                traceData.topLevelFailureCount()
        );
        artifact.setTopLevelSkippedCount(
                traceData.topLevelSkippedCount()
        );

        artifact.setDescendantTotalCount(
                traceData.descendantTotalCount()
        );
        artifact.setDescendantSuccessCount(
                traceData.descendantSuccessCount()
        );
        artifact.setDescendantFailureCount(
                traceData.descendantFailureCount()
        );
        artifact.setDescendantSkippedCount(
                traceData.descendantSkippedCount()
        );

        artifact.setPlannedChunkCount(
                chunkPlan.totalChunks()
        );
        artifact.setStoredChunkCount(0);
        artifact.setSourceCharCount(
                chunkPlan.sourceCharCount()
        );
        artifact.setChunkCharCount(
                chunkPlan.chunkCharCount()
        );

        artifact.setPayloadChecksum(
                calculatePlanChecksum(chunkPlan)
        );

        artifact.setFieldSemanticsJson(
                writeJson(fieldPolicy.visibleFields())
        );

        artifact.setExpiresAt(
                now.plusHours(retentionHours)
        );
        artifact.setCreatedAt(now);

        if (artifactMapper.insert(artifact) != 1) {
            throw new IllegalStateException(
                    "工作流结果快照创建失败"
            );
        }

        int storedChunkCount = 0;

        /*
         * 中文注释：
         * 这里直接逐块插入，逻辑最简单且不会产生超大批量SQL。
         * 现有分块最大约12KB，即使数据较多也不会单条写入过大。
         */
        for (WorkflowAnswerChunk chunk :
                chunkPlan.chunks()) {

            ResultArtifactChunk entity =
                    new ResultArtifactChunk();

            entity.setArtifactId(artifactId);
            entity.setChunkNo(chunk.index());
            entity.setSourcePointer(
                    chunk.sourcePointer()
            );
            entity.setStartIndex(
                    chunk.startIndex()
            );
            entity.setEndIndex(
                    chunk.endIndex()
            );
            entity.setPayloadJson(
                    chunk.payloadJson()
            );
            entity.setPayloadSha256(
                    chunk.sha256()
            );
            entity.setCharCount(
                    chunk.charCount()
            );
            entity.setCreatedAt(now);
            if (chunkMapper.insert(entity) != 1) {
                throw new IllegalStateException(
                        "工作流结果快照分块写入失败："
                                + chunk.index()
                );
            }
            storedChunkCount++;
        }

        /*
         * 实际数量与计划数量不一致时明确失败，
         * 禁止把不完整快照标记成COMPLETE。
         */
        if (storedChunkCount != chunkPlan.totalChunks()) {

            throw new IllegalStateException(
                    "工作流结果快照不完整，计划分块数："
                            + chunkPlan.totalChunks()
                            + "，实际保存数："
                            + storedChunkCount
            );
        }

        ResultArtifact completed =new ResultArtifact();

        completed.setId(artifactId);
        completed.setStatus("COMPLETE");
        completed.setStoredChunkCount(storedChunkCount);
        completed.setCompletedAt(LocalDateTime.now());

        if (artifactMapper.updateById(completed) != 1) {
            throw new IllegalStateException(
                    "工作流结果快照完成状态更新失败"
            );
        }

        return artifactId;
    }

    private void validate( AgentRequest request, WorkflowExecutionOutcome outcome,WorkflowAnswerChunkPlan chunkPlan) {

        if (request == null
                || !StringUtils.hasText(
                        request.getUserId())
                || !StringUtils.hasText(
                        request.getConversationId())) {

            throw new IllegalArgumentException(
                    "结果快照缺少用户或会话信息"
            );
        }

        if (outcome == null|| !StringUtils.hasText(outcome.runId())|| !StringUtils.hasText(outcome.workflowCode())) {
            throw new IllegalArgumentException(
                    "结果快照缺少工作流运行信息"
            );
        }

        if (chunkPlan == null || chunkPlan.totalChunks() <= 0|| chunkPlan.chunks().size()!= chunkPlan.totalChunks()) {
            throw new IllegalArgumentException(
                    "结果快照分块计划不完整"
            );
        }
    }

    /**
     * 对“分块序号 + 分块摘要”再次生成总摘要。
     *
     * 加入分块序号可以识别分块顺序发生变化的情况。
     */
    private String calculatePlanChecksum( WorkflowAnswerChunkPlan chunkPlan) {

        String source = chunkPlan.chunks()
                .stream()
                .map(chunk ->
                        chunk.index()
                                + ":"
                                + chunk.sha256()
                )
                .collect(
                        Collectors.joining("|")
                );

        return sha256(source);
    }

    private String sha256(String source) {
        try {
            MessageDigest digest =MessageDigest.getInstance("SHA-256");

            byte[] bytes = digest.digest(
                    source.getBytes(
                            StandardCharsets.UTF_8
                    )
            );

            return HexFormat.of()
                    .formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "结果快照摘要计算失败",
                    exception
            );
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "字段语义快照序列化失败",
                    exception
            );
        }
    }

    /**
     * 读取当前用户、当前会话的完整结果快照。
     *
     * 任何归属、过期或完整性校验失败，
     * 都不得把数据交给回答模型。
     */
    public ResultArtifactSnapshot loadComplete(
            String userId,
            String sessionId,
            String artifactId) {
        if (!StringUtils.hasText(userId)
                || !StringUtils.hasText(sessionId)
                || !StringUtils.hasText(artifactId)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "没有找到可分析的上一轮查询结果，请先完成业务查询"
            );
        }
        /*
         * 中文注释：
         * 必须同时匹配artifactId、用户和会话，
         * 防止跨用户、跨会话读取结果。
         */
        ResultArtifact artifact =
                artifactMapper.selectOne(
                        Wrappers.<ResultArtifact>lambdaQuery()
                                .eq(ResultArtifact::getId,artifactId)
                                .eq(ResultArtifact::getUserId,userId)
                                .eq( ResultArtifact::getSessionId,sessionId)
                                .eq(ResultArtifact::getStatus,"COMPLETE")
                                .last("LIMIT 1")
                );

        /*
         * 不区分“快照不存在”和“无权读取”，
         * 避免泄露其他用户是否存在该快照。
         */
        if (artifact == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "没有找到可分析的上一轮查询结果，请重新查询"
            );
        }

        if (artifact.getExpiresAt() == null
                || artifact.getExpiresAt()
                .isBefore(LocalDateTime.now())) {

            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "上一轮查询结果已过期，请重新查询业务数据"
            );
        }

        List<ResultArtifactChunk> entities =
                chunkMapper.selectList(
                        Wrappers.<ResultArtifactChunk>lambdaQuery()
                                .eq(ResultArtifactChunk::getArtifactId,artifactId)
                                .orderByAsc(ResultArtifactChunk::getChunkNo)
                );

        if (!Objects.equals(artifact.getPlannedChunkCount(),artifact.getStoredChunkCount()) ||
                artifact.getStoredChunkCount() == null || entities.size()
                != artifact.getStoredChunkCount()) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "上一轮查询结果快照不完整，请重新查询"
            );
        }

        List<WorkflowAnswerChunk> chunks =
                entities.stream()
                        .map(this::toVerifiedChunk)
                        .toList();

        /*
         * 分块序号必须从1连续递增。
         */
        for (int index = 0; index < chunks.size(); index++) {
            int expected = index + 1;
            if (chunks.get(index).index() != expected) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR,
                        "上一轮查询结果分块顺序异常，请重新查询"
                );
            }
        }
        WorkflowAnswerChunkPlan chunkPlan = new WorkflowAnswerChunkPlan(
                        chunks.size(),
                        artifact.getSourceCharCount(),
                        artifact.getChunkCharCount(),
                        chunks
                );

        /*
         * 中文注释：
         * 校验全部分块的顺序摘要，
         * 防止数据库内容被异常修改后继续参与回答。
         */
        String actualChecksum =
                calculatePlanChecksum(chunkPlan);

        if (!Objects.equals( artifact.getPayloadChecksum(),actualChecksum)) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "上一轮查询结果完整性校验失败，请重新查询"
            );
        }

        return new ResultArtifactSnapshot(
                artifact,
                chunkPlan,
                artifact.getFieldSemanticsJson()
        );
    }

    /**
     * 将数据库分块转换为回答分块，
     * 同时校验分块基础信息、字符数和SHA-256。
     */
    private WorkflowAnswerChunk toVerifiedChunk(
            ResultArtifactChunk entity) {

        if (entity == null
                || entity.getChunkNo() == null
                || entity.getChunkNo() <= 0
                || entity.getPayloadJson() == null
                || entity.getCharCount() == null
                || entity.getCharCount() < 0) {

            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "上一轮查询结果分块结构异常，请重新查询"
            );
        }

        /*
         * 字符数量必须与实际JSON完全一致。
         */
        if (entity.getPayloadJson().length()
                != entity.getCharCount()) {

            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "上一轮查询结果分块长度校验失败，请重新查询"
            );
        }

        String actualSha256 =
                ContentHashUtils.sha256(
                        entity.getPayloadJson()
                );

        if (!Objects.equals(
                entity.getPayloadSha256(),
                actualSha256)) {

            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "上一轮查询结果分块完整性校验失败，请重新查询"
            );
        }
        return new WorkflowAnswerChunk(
                entity.getChunkNo(),
                entity.getSourcePointer(),
                entity.getStartIndex(),
                entity.getEndIndex(),
                entity.getPayloadJson(),
                entity.getPayloadSha256(),
                entity.getCharCount()
        );
    }
}