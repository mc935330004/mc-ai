package org.example.ai.agent.common.modelusage;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.common.enums.ModelCallType;

/**
 * 模型调用上下文。
 *
 * 每次调用模型前构建，用于关联 Agent 运行、会话、用户和调用阶段。
 */
@Data
@Builder
public class ModelCallContext {

    /**
     * Agent 本次运行 ID。
     *
     * 字段语义生成、独立聊天等非 Agent 请求可以为空。
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
     * 模型调用类型。
     */
    private ModelCallType callType;

    /**
     * 同一调用类型下的调用序号。
     */
    @Builder.Default
    private int callSequence = 1;
}