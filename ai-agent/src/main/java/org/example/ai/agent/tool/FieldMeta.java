package org.example.ai.agent.tool;

import lombok.Builder;
import lombok.Data;

/**
 * 字段语义元数据。
 *
 * 来自 ai_field_dictionary，
 * 用于告诉大模型 contractAmount、receivedAmount 等字段的业务含义。
 */
@Data
@Builder
public class FieldMeta {

    /**
     * 字段英文名。
     */
    private String name;

    /**
     * 字段中文名。
     */
    private String cnName;

    /**
     * 字段路径，例如 $.data.contractAmount。
     */
    private String path;

    /**
     * 字段类型，例如 string、number、date。
     */
    private String type;

    /**
     * 展示格式，例如 amount、date、percent。
     */
    private String format;

    /**
     * 业务含义说明。
     */
    private String meaning;
}