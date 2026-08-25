package org.example.ai.agent.common.enums.protocol;

/**
 * CHAT和REPORT共用的区块类型。
 *
 * 第一阶段只保留当前确实需要实现的类型，
 * 不提前加入图表、导出等未实现能力。
 */
public enum BlockType {

    /**
     * 自然语言说明。
     *
     * 只有该类型允许使用Markdown。
     */
    TEXT,

    /**
     * 金额、比例和数量等核心指标。
     */
    METRICS,

    /**
     * 项目名称、项目经理等基础信息。
     */
    KEY_VALUE,

    /**
     * 普通列表数据。
     */
    TABLE,

    /**
     * 树形层级列表。
     */
    TREE_TABLE,

    /**
     * 分组列表。
     */
    GROUP_TABLE,

    /**
     * 需要重点展示的结论。
     */
    CALLOUT,

    /**
     * 审批状态、执行状态等确定性状态。
     */
    STATUS,

    /**
     * 风险规则产生的风险提示。
     */
    WARNINGS
}