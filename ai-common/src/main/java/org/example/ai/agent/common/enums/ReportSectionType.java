package org.example.ai.agent.common.enums;

/**
 * 后端可配置的通用报告区块类型。
 */
public enum ReportSectionType {

    KEY_VALUE,

    METRICS,

    TABLE,

    TREE_TABLE,
    /**
     * 分组明细表。
     *
     * 先根据分组路径读取分组数据，
     * 再从每个分组中读取相对明细列表。
     */
    GROUP_TABLE,
}