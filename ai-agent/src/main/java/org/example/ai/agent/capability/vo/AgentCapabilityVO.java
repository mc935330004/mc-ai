package org.example.ai.agent.capability.vo;

import lombok.Data;

/**
 * Agent 可用能力 VO。
 *
 * 只暴露给 Agent 选择工具所需的最小信息。
 */
@Data
public class AgentCapabilityVO {

    /**
     * 能力编码，例如：pm.project.getByName。
     */
    private String capabilityCode;

    /**
     * 能力名称。
     */
    private String capabilityName;

    /**
     * 业务域，例如：pm、contract、payment。
     */
    private String domain;

    /**
     * 模块名称。
     */
    private String moduleName;

    /**
     * 能力说明。
     * 重点：这里要描述“用户问什么问题时应该调用这个能力”。
     */
    private String description;

    /**
     * 入参说明 JSON。
     */
    private String inputSchemaJson;
}