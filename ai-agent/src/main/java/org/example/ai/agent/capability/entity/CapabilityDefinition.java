package org.example.ai.agent.capability.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * AI 能力定义实体。
 *
 * 一条记录代表一个 Agent 可调用的业务能力。
 */
@Data
@TableName("ai_capability_definition")
public class CapabilityDefinition {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 能力编码。
     *
     * 示例：
     * pm.project.getByName
     * pm.contract.listByProjectId
     */
    private String capabilityCode;

    /**
     * 能力名称。
     */
    private String capabilityName;

    /**
     * 业务域。
     *
     * 示例：
     * pm、contract、payment
     */
    private String domain;

    /**
     * 模块名称。
     */
    private String moduleName;

    /**
     * 能力说明。
     *
     * 用于告诉开发人员和大模型这个能力能做什么。
     */
    private String description;

    /**
     * 请求方法。
     *
     * 示例：
     * GET、POST
     */
    private String method;

    /**
     * 真实业务接口地址。
     */
    private String url;

    /**
     * 副作用级别。
     *
     * READ：只读查询
     * WRITE：写操作，需要人工确认
     * DANGEROUS：危险操作，第一版禁止自动执行
     */
    private String sideEffect;

    /**
     * 是否启用。1启用，0停用
     */
    private Integer enabled;

    /**
     * 入参 JSON Schema。
     */
    private String inputSchemaJson;

    /**
     * 出参 JSON Schema。
     */
    private String outputSchemaJson;

    /**
     * 调用示例 JSON。
     */
    private String exampleJson;

    /**
     * 创建时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 请求内容类型。
     */
    private String requestContentType;
    /**
     * 接口超时时间。
     */
    private Integer timeoutMs;
    /**
     * 是否需要用户确认。
     */
    private Boolean requireConfirm;

    /**
     * 备注。
     */
    private String remark;
}