package org.example.ai.agent.answer.model;

import lombok.Builder;
import lombok.Data;

/**
 * 发送给大模型的精简事实。
 */
@Data
@Builder
public class AnswerModelFact {

    /**
     * 字段中文名称。
     */
    private String label;

    /**
     * 字段展示值。
     */
    private String value;

    /**
     * 字段业务含义。
     */
    private String meaning;

    /**
     * 是否为必答字段。
     */
    private boolean required;

    /**
     * 字段所属分组。
     */
    private String group;
}