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

    /**
     * 所属业务系统编码。
     *
     * 为空时继续使用当前默认 BusinessApiProperties.baseUrl。
     */
    private String systemCode;

    /**
     * 配置来源。
     *
     * MANUAL：人工创建。
     * OPENAPI：通过 OpenAPI 导入。
     */
    private String sourceType;

    /**
     * OpenAPI 接口唯一标识 operationId。
     */
    private String sourceOperationId;

    /**
     * 发布状态。
     *
     * DRAFT：草稿，不允许 Agent 调用。
     * PUBLISHED：已发布。
     * DISABLED：已停用。
     */
    private String publishStatus;

    /**
     * 请求参数绑定配置。
     *
     * 描述逻辑输入如何转换成真实业务 API 的：
     * PATH、QUERY、BODY 参数。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String requestBindingJson;

    /**
     * 响应解释配置。
     *
     * 第一阶段先保存 JSON，L0-4 再增加完整强类型模型。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String responseBindingJson;

    /**
     * 配置修订号。
     *
     * 每次保存草稿时递增，发布动作本身不递增。
     */
    private Integer configRevision;

    /**
     * 当前 activeVersionId 对应发布版本的 SHA-256。
     *
     * 编辑草稿时不能清空该值，
     * 因为已经发布的运行版本仍然有效。
     */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String configChecksum;

    /**
     * 最近一次通过发布校验的时间。
     *
     * 草稿被重新编辑时必须清空。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime validatedAt;

    /**
     * 当前正式运行的能力版本ID。
     *
     * Agent 正常调用必须读取该版本的 snapshotJson，
     * 不能直接读取主表中正在编辑的草稿字段。
     */
    private Long activeVersionId;

    /**
     * 草稿是否存在未发布修改。
     *
     * 1：草稿与当前发布版本不同；
     * 0：草稿已经发布。
     */
    private Integer draftDirty;
}