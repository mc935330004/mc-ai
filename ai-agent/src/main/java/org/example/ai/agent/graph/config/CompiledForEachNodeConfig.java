package org.example.ai.agent.graph.config;

import org.example.ai.agent.graph.compiler.CompiledGraphSpec;

/**
 * 编译后的 FOREACH 配置。
 */
public record CompiledForEachNodeConfig(

        String itemsExpression,

        int maxItems,

        int concurrency,

        boolean continueOnItemError,

        /**
         * true 表示遍历业务系统返回的全部记录。
         *
         * 只解除记录总数限制，不解除并发限制。
         */
        boolean processAllItems,

        /**
         * 缺值跳过策略。
         */
        ForEachMissingValueSkip missingValueSkip,

        CompiledGraphSpec body)

        implements GraphNodeConfig {
}