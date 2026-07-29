package org.example.ai.agent.common.enums;

/**
 * 告警来源类型。
 *
 * 首版只处理工作流运行告警。
 * 后续可以直接增加 CAPABILITY、MODEL、KNOWLEDGE 等来源。
 */
public enum AlertSourceType {

    /**
     * 工作流运行记录。
     */
    WORKFLOW_RUN
}