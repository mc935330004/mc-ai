package org.example.ai.agent.capability.dto;

import lombok.Data;

/**
 * AI 能力保存 DTO。
 *
 * 第一阶段只负责 ai_capability_definition，不处理字段字典。
 */
@Data
public class CapabilitySaveDTO {

    /**
     * 主键 ID。
     * 新增时为空，修改时必传。
     */
    private Long id;

    /**
     * 能力编码，例如：pm.project.getByName。
     */
    private String capabilityCode;

    /**
     * 能力名称，例如：根据项目名称查询项目。
     */
    private String capabilityName;

    /**
     * 业务域，例如：pm、contract、payment。
     */
    private String domain;

    /**
     * 模块名称，例如：项目管理、合同管理。
     */
    private String moduleName;

    /**
     * 能力说明。
     * 这里要写给大模型看：什么时候该调用这个能力。
     */
    private String description;

    /**
     * 请求方法：GET 或 POST。
     */
    private String method;

    /**
     * 业务接口地址。
     * 建议保存相对路径，例如：/api/project/getByName。
     */
    private String url;

    /**
     * 副作用等级。
     * 第一阶段只允许 READ。
     */
    private String sideEffect;

    /**
     * 入参 Schema 或示例 JSON。
     */
    private String inputSchemaJson;

    /**
     * 出参 Schema 或示例 JSON。
     */
    private String outputSchemaJson;

    /**
     * 调用示例 JSON。
     */
    private String exampleJson;
    /**
     * 是否启用。
     * 1：启用
     * 0：停用
     */
    private Integer enabled;

    /**
     * 请求内容
     */
    private String requestContentType;

    /**
     * 是否需要用户确认。
     */
    private Boolean requireConfirm;

    /**
     * 所属业务系统编码。
     */
    private String systemCode;

    /**
     * 配置来源：MANUAL/OPENAPI。
     */
    private String sourceType;

    /**
     * OpenAPI operationId。
     */
    private String sourceOperationId;

    /**
     * 发布状态：DRAFT 草稿、PUBLISHED 已发布、DISABLED 已停用。
     */
    private String publishStatus;
}
