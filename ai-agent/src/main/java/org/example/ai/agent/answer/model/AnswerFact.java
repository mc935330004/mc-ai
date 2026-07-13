package org.example.ai.agent.answer.model;

import lombok.Builder;
import lombok.Data;

/**
 * 从业务接口真实数据和字段字典中提取出的标准事实。
 *
 * 最终 Markdown 只能基于 AnswerFact 生成，
 * 不再直接遍历原始业务接口 JSON。
 */
@Data
@Builder
public class AnswerFact {

    /**
     * 事实唯一键。
     *
     * 格式：
     * capabilityCode:fieldPath:recordPath
     */
    private String key;

    /**
     * 业务能力编码。
     */
    private String capabilityCode;

    /**
     * 原始字段名。
     */
    private String fieldName;

    /**
     * 字段字典配置的 JSON 路径。
     */
    private String fieldPath;

    /**
     * 字段中文展示名称。
     */
    private String label;

    /**
     * 原始字段值。
     */
    private Object value;

    /**
     * 确定性格式化后的展示值。
     */
    private String displayValue;

    /**
     * 字段数据类型。
     */
    private String valueType;

    /**
     * 字段展示格式。
     */
    private String displayFormat;

    /**
     * 字段业务含义。
     */
    private String meaning;

    /**
     * 字段展示分组。
     */
    private String displayGroup;

    /**
     * 字段展示顺序。
     */
    private Integer displayOrder;

    /**
     * 是否为必答字段。
     */
    private boolean required;

    /**
     * 字段是否缺失。
     */
    private boolean missing;

    /**
     * 缺失原因。
     *
     * 示例：PATH_NOT_FOUND、VALUE_NULL、ARRAY_EMPTY。
     */
    private String missingReason;

    /**
     * 当前字段所属记录路径。
     *
     * 示例：
     * $.data.records[0]
     */
    private String recordPath;

    /**
     * 当前字段所属集合。
     *
     * 示例：
     * capabilityCode:$.data.records[]
     */
    private String collectionKey;
}