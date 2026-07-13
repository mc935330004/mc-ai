package org.example.ai.agent.modelusage.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.TokenMeasureType;
import org.example.ai.agent.modelusage.entity.ModelUsageRecord;
import org.example.ai.agent.modelusage.mapper.ModelUsageMapper;
import org.example.ai.agent.modelusage.model.ModelCallContext;
import org.example.ai.agent.modelusage.model.TokenUsageData;
import org.example.ai.agent.modelusage.service.ModelUsageService;
import org.example.ai.agent.trace.mapper.RunTraceMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 大模型 Token 使用记录 Service 实现。
 */
@Service
@RequiredArgsConstructor
public class ModelUsageServiceImpl implements ModelUsageService {

    private final ModelUsageMapper modelUsageMapper;
    private final RunTraceMapper runTraceMapper;

    /**
     * 使用独立事务保存 Token。
     *
     * 原因：
     * Token 统计属于辅助能力，不应该和聊天主事务强绑定。
     * 外层调用仍需要捕获异常，确保统计失败不影响用户回答。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess( ModelCallContext context, String provider,String modelName, String requestId,
                               TokenUsageData usage,long durationMs,String finishReason) {
        TokenUsageData safeUsage = usage == null ? TokenUsageData.unknown() : usage;
        ModelUsageRecord record = buildBaseRecord(context);
        record.setProvider(provider);
        record.setModelName(modelName);
        record.setRequestId(requestId);
        record.setPromptTokens(safeUsage.getPromptTokens());
        record.setCompletionTokens(safeUsage.getCompletionTokens());
        record.setTotalTokens(safeUsage.getTotalTokens());
        record.setCacheReadTokens(safeUsage.getCacheReadTokens());
        record.setCacheWriteTokens(safeUsage.getCacheWriteTokens());
        record.setMeasureType(safeUsage.getMeasureType() == null ? TokenMeasureType.UNKNOWN.name()
                : safeUsage.getMeasureType().name());
        record.setDurationMs(Math.max(durationMs, 0));
        record.setFinishReason(limit(finishReason, 32));
        record.setSuccess(1);
        modelUsageMapper.insert(record);

        /*
         * 只有存在 runId 时，才汇总到聊天运行主记录。
         * 字段语义生成等管理功能可能没有 runId。
         */
        if (context != null && StringUtils.hasText(context.getRunId())) {
            runTraceMapper.incrementTokenUsage( context.getRunId(),safeUsage.getPromptTokens(),
                    safeUsage.getCompletionTokens(),safeUsage.getTotalTokens());
        }
    }

    /**
     * 保存失败调用。
     *
     * 失败调用也需要记录调用次数和耗时，
     * 但由于供应商通常不返回 Usage，这里不累加 Token。
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure( ModelCallContext context, String provider,String modelName,
                               long durationMs, String errorMessage) {
        ModelUsageRecord record = buildBaseRecord(context);
        record.setProvider(provider);
        record.setModelName(modelName);

        // 供应商调用失败时通常拿不到精确 Token，因此保存为 0。
        record.setPromptTokens(0);
        record.setCompletionTokens(0);
        record.setTotalTokens(0);
        record.setMeasureType(TokenMeasureType.UNKNOWN.name());

        record.setDurationMs(Math.max(durationMs, 0));
        record.setSuccess(0);
        record.setErrorMessage(limit(errorMessage, 512));

        modelUsageMapper.insert(record);
        /*
         * 失败调用同样属于一次真实模型调用，
         * 因此需要增加 model_call_count。
         *
         * Token 暂时按 0 累加，避免将未知 Token 伪造成真实值。
         */
        if (context != null && StringUtils.hasText(context.getRunId())) {
            runTraceMapper.incrementTokenUsage(context.getRunId(),0,0,0 );
        }
    }

    @Override
    public List<ModelUsageRecord> listByRunIdAndUserId(String runId, String userId) {
        if (!StringUtils.hasText(runId) || !StringUtils.hasText(userId)) {
            return List.of();
        }
        return modelUsageMapper.selectList( new LambdaQueryWrapper<ModelUsageRecord>()
                        .eq(ModelUsageRecord::getRunId, runId)
                        .eq(ModelUsageRecord::getUserId, userId)
                        .orderByAsc(ModelUsageRecord::getId));
    }

    /**
     * 构建公共字段。
     */
    private ModelUsageRecord buildBaseRecord(ModelCallContext context) {
        ModelUsageRecord record = new ModelUsageRecord();
        if (context != null) {
            record.setRunId(context.getRunId());
            record.setConversationId(context.getConversationId());
            record.setUserId(context.getUserId());
            record.setCallType(context.getCallType() == null ? "UNKNOWN" : context.getCallType().name());
            record.setCallSequence(Math.max(context.getCallSequence(), 1));
        } else {
            record.setCallType("UNKNOWN");
            record.setCallSequence(1);
        }
        record.setCreatedAt(LocalDateTime.now());
        return record;
    }

    /**
     * 限制数据库字符串长度，避免统计逻辑再次抛出字段超长异常。
     */
    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}