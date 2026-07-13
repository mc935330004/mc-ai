package org.example.ai.agent.answer.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 发送给最终回答模型的精简工具上下文。
 *
 * 该对象明确排除：
 * 1. raw 原始接口响应；
 * 2. 工具调用入参；
 * 3. 内部执行步骤；
 * 4. Authorization；
 * 5. 重复字段元数据。
 */
@Data
@Builder
public class AnswerToolContext {

    /**
     * 能力编码仅用于模型区分不同业务数据源。
     *
     * 提示词会明确要求模型不得向用户输出该编码。
     */
    private String capabilityCode;

    /**
     * 工具调用摘要。
     */
    private String summary;

    /**
     * 经过字段字典压缩后的业务数据。
     */
    private Object data;

    /**
     * 精简后的字段说明。
     */
    private List<AnswerFieldContext> fields;

    /**
     * 发送给模型的精简事实。
     *
     * 不包含原始 JSON、字段路径和内部唯一键。
     */
    private List<AnswerModelFact> facts;
}