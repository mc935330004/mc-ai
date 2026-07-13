package org.example.ai.agent.answer.model;

import lombok.Builder;
import lombok.Data;

/**
 * 发送给回答模型的精简字段说明。
 */
@Data
@Builder
public class AnswerFieldContext {

    /**
     * 字段展示名称。
     */
    private String label;

    /**
     * 业务含义。
     */
    private String meaning;

    /**
     * 展示格式，例如 amount、date、percent。
     */
    private String format;
}