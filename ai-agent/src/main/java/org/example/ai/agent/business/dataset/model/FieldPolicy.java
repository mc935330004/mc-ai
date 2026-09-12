package org.example.ai.agent.business.dataset.model;

/**
 * 标准事实字段在统计、展示、导出和模型上下文中的不可变使用策略。
 */
public record FieldPolicy(
        String factCode,
        String factType,
        boolean calculable,
        boolean displayable,
        boolean exportable,
        boolean modelVisible,
        String maskStrategy,
        String grain) {
}
