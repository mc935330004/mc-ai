package org.example.ai.agent.tool;

import lombok.Builder;
import lombok.Data;

/**
 * 字段统一元数据。
 *
 * 来自已发布字段字典，
 * 用于事实提取、权限过滤和Block规划。
 */
@Data
@Builder
public class FieldMeta {

    /**
     * 字段机器名称。
     */
    private String name;

    /**
     * 字段业务语义编码。
     */
    private String fieldCode;

    /**
     * 字段中文名称。
     */
    private String cnName;

    /**
     * 字段完整取值路径。
     */
    private String path;

    /**
     * 字段数据类型。
     */
    private String type;

    /**
     * 展示格式。
     *
     * 例如：amount、date、percent、status。
     */
    private String format;

    /**
     * 枚举值映射JSON。
     */
    private String enumMappingJson;

    /**
     * 字段业务含义。
     */
    private String meaning;

    /**
     * 是否为必答字段。
     */
    private Integer requiredOutput;

    /**
     * 是否允许发送给大模型。
     */
    private Integer modelVisible;

    /**
     * 是否允许展示给用户。
     */
    private Integer userVisible;

    /**
     * 展示顺序。
     */
    private Integer displayOrder;

    /**
     * 展示分组。
     */
    private String displayGroup;

    /**
     * 空值展示文本。
     */
    private String nullDisplayText;

    /**
     * 字段重要程度。
     */
    private String importance;

    /**
     * 建议展示组件。
     */
    private String displayComponent;

    /**
     * 是否优先进入汇总结果。
     */
    private Integer summaryFlag;

    /**
     * 字段单位。
     */
    private String unit;

    /**
     * 数字展示精度。
     */
    private Integer precisionScale;

    /**
     * 字段值来源。
     */
    private String valueSource;
}