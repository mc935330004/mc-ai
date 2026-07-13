package org.example.ai.agent.modelusage.model;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.common.enums.ModelCallType;

/**
 * 模型调用上下文。
 *
 * 每次调用模型前构建，用来关联 runId、用户、会话和调用类型。
 */
@Data
@Builder
public class ModelCallContext {

    /**
     * Agent 本次运行 ID。
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
     *
     * 第一阶段最终回答通常只有一次，因此默认传 1。
     */
    @Builder.Default
    private int callSequence = 1;
}