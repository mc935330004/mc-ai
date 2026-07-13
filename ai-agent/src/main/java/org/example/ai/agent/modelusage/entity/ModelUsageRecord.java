package org.example.ai.agent.modelusage.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 大模型调用 Token 使用明细实体。
 *
 * 对应数据库表：ai_model_usage。
 */
@Data
@TableName("ai_model_usage")
public class ModelUsageRecord {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Agent 运行 ID。
     */
    private String runId;

    /**
     * 会话 ID。
     */
    private String conversationId;

    /**
     * 用户 ID。
     */
    private String userId;

    /**
     * 调用类型。
     */
    private String callType;

    /**
     * 同类型调用序号。
     */
    private Integer callSequence;

    /**
     * 模型供应商。
     */
    private String provider;

    /**
     * 实际处理请求的模型名称。
     */
    private String modelName;

    /**
     * 模型供应商返回的请求 ID。
     */
    private String requestId;

    /**
     * 输入 Token。
     */
    private Integer promptTokens;

    /**
     * 输出 Token。
     */
    private Integer completionTokens;

    /**
     * 总 Token。
     */
    private Integer totalTokens;

    /**
     * 从提示词缓存读取的 Token。
     */
    private Long cacheReadTokens;

    /**
     * 写入提示词缓存的 Token。
     */
    private Long cacheWriteTokens;

    /**
     * Token 计量方式。
     */
    private String measureType;

    /**
     * 模型调用耗时。
     */
    private Long durationMs;

    /**
     * 模型结束原因。
     */
    private String finishReason;

    /**
     * 是否调用成功。
     */
    private Integer success;

    /**
     * 错误信息摘要。
     */
    private String errorMessage;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}