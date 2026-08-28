package org.example.ai.agent.capability.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

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
     * 字段业务语义编码。
     *
     * fieldPath负责定位真实数据，
     * fieldCode负责识别业务语义。
     */
    private String fieldCode;

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
     * 枚举原始值与展示文字的映射配置。
     *
     * 仅在 displayFormat=enum 时生效。
     */
    private String enumMappingJson;

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
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 字段来源：MANUAL、OPENAPI、SAMPLE、AI。
     */
    private String sourceType;

    /**
     * 是否经过人工确认。
     *
     * 1：后续自动同步不能覆盖。
     * 0：允许自动补充。
     */
    private Integer manualOverride;
    /**
     * 是否为最终回答必答字段。
     *
     * 1：只要字段允许展示，最终 Markdown 必须出现。
     * 0：允许根据回答场景选择性展示。
     */
    private Integer requiredOutput;

    /**
     * 是否允许发送给大模型。
     *
     * 1：允许。
     * 0：仅供工作流内部使用。
     */
    private Integer modelVisible;

    /**
     * 是否允许展示给用户。
     *
     * 1：允许。
     * 0：不允许进入文字回答、列表和报告。
     */
    private Integer userVisible;

    /**
     * 字段展示顺序。
     *
     * 数值越小，在 Markdown 中越靠前。
     */
    private Integer displayOrder;

    /**
     * 字段展示分组。
     *
     * 示例：基本信息、合同信息、进度信息。
     */
    private String displayGroup;

    /**
     * 字段重要程度：HIGH、NORMAL、LOW。
     */
    private String importance;

    /**
     * 建议展示组件。
     *
     * 支持：
     * AUTO、METRICS、KEY_VALUE、TABLE、STATUS、HIDDEN。
     */
    private String displayComponent;

    /**
     * 是否优先进入汇总结果。
     *
     * 1：是。
     * 0：否。
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
     * RAW、CALCULATED、AGGREGATED、RULE_EVALUATED。
     */
    private String valueSource;

    /**
     * 字段值为 null 或不存在时的展示文本。
     */
    private String nullDisplayText;

    /**
     * 发布状态：DRAFT、PUBLISHED、DISABLED。
     */
    private String publishStatus;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}