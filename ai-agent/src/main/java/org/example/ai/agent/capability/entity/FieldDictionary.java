package org.example.ai.agent.capability.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 字段语义字典实体。
 *
 * 用来解释业务接口返回字段的含义。
 */
@Data
@TableName("ai_field_dictionary")
public class FieldDictionary {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 能力编码。
     *
     * 用于关联 ai_capability_definition.capability_code。
     */
    private String capabilityCode;

    /**
     * 字段路径。
     *
     * 示例：
     * $.data.contractAmount
     */
    private String fieldPath;

    /**
     * 字段英文名。
     */
    private String fieldName;

    /**
     * 字段中文名。
     */
    private String fieldCnName;

    /**
     * 字段类型。
     *
     * 示例：
     * string、number、date
     */
    private String fieldType;

    /**
     * 业务含义。
     */
    private String businessMeaning;

    /**
     * 展示格式。
     *
     * 示例：
     * amount、date、percent
     */
    private String displayFormat;

    /**
     * 示例值。
     */
    private String exampleValue;

    /**
     * 是否可搜索。 0 是  1 否
     */
    private Integer searchable;

    /**
     * 是否可聚合统计。
     * 0 是  1 否
     */
    private Integer aggregatable;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;
}