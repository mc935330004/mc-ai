package org.example.ai.agent.common.enums;

/**
 * 事实数据来源。
 *
 * AI只能解释事实，不能修改事实来源和真实值。
 */
public enum FactSourceType {

    /**
     * 业务接口直接返回的原始事实。
     */
    RAW,

    /**
     * 后端计算公式产生的事实。
     */
    CALCULATED,

    /**
     * 后端确定性聚合产生的事实。
     */
    AGGREGATED,

    /**
     * 风险规则判定产生的事实。
     */
    RULE_EVALUATED
}