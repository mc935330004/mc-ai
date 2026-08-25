package org.example.ai.agent.common.enums.protocol;

/**
 * 区块数据来源。
 *
 * 用于区分真实业务数据、计算结果、
 * 风险规则结果和AI生成内容。
 */
public enum BlockSource {

    /**
     * 来自业务接口或工作流的真实数据。
     */
    BUSINESS,

    /**
     * 由后端公式计算产生。
     */
    CALCULATION,

    /**
     * 由确定性业务规则产生。
     */
    RULE,

    /**
     * 由大模型生成。
     */
    AI,

    /**
     * 由系统生成的固定提示。
     */
    SYSTEM
}