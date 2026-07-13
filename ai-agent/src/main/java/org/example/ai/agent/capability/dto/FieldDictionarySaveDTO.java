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
     * 是否允许展示。
     */
    private Integer visible;

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
}