package org.example.ai.agent.capability.dto;

import lombok.Data;

/**
 * AI 字段字典保存 DTO。
 *
 * 用来维护某个能力返回字段的业务含义。
 */
@Data
public class FieldDictionarySaveDTO {

    /**
     * 主键 ID。
     * 新增时为空，修改时必传。
     */
    private Long id;

    /**
     * 能力编码，例如：pm.contract.listByProjectId。
     */
    private String capabilityCode;

    /**
     * 字段路径，例如：$.data.records[].contractAmount。
     */
    private String fieldPath;

    /**
     * 字段英文名，例如：contractAmount。
     */
    private String fieldName;

    /**
     * 字段业务语义编码。
     *
     * 不填写时默认使用fieldName。
     */
    private String fieldCode;

    /**
     * 字段中文名，例如：合同金额。
     */
    private String fieldCnName;

    /**
     * 字段类型，例如：string、number、date。
     */
    private String fieldType;

    /**
     * 业务含义，给大模型解释字段用。
     */
    private String businessMeaning;

    /**
     * 展示格式，例如：amount、date、percent、text。
     */
    private String displayFormat;

    /**
     * 枚举值映射 JSON。
     *
     * 示例：{"0":"审批中","1":"审批通过"}
     */
    private String enumMappingJson;

    /**
     * 示例值。
     */
    private String exampleValue;

    /**
     * 是否可搜索：0 是，1 否。
     */
    private Integer searchable;

    /**
     * 是否可聚合统计：0 是，1 否。
     */
    private Integer aggregatable;

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
     * 字段重要程度：HIGH、NORMAL、LOW。
     */
    private String importance;

    /**
     * 建议展示组件。
     *
     * 不填写时默认AUTO。
     */
    private String displayComponent;

    /**
     * 是否优先进入汇总结果。
     */
    private Integer summaryFlag;

    /**
     * 字段展示单位。
     */
    private String unit;

    /**
     * 数字展示精度。
     */
    private Integer precisionScale;

    /**
     * 字段值来源。
     *
     * 不填写时默认RAW。
     */
    private String valueSource;

    /**
     * 空值展示文本。
     */
    private String nullDisplayText;
}