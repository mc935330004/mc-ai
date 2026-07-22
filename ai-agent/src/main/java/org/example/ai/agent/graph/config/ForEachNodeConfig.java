package org.example.ai.agent.graph.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.example.ai.agent.graph.model.GraphSpec;

/**
 * FOREACH 草稿配置。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ForEachNodeConfig(

        /**
         * 需要遍历的集合表达式。
         */
        String itemsExpression,

        /**
         * 普通循环的最大项目数。
         *
         * projectKeys 等用户输入集合可以继续限制为 5。
         */
        Integer maxItems,

        /**
         * 最大并发数。
         */
        Integer concurrency,

        /**
         * 单条执行失败后是否继续下一条。
         */
        Boolean continueOnItemError,

        /**
         * 是否处理上游业务接口返回的全部记录。
         *
         * true：
         * 不使用 maxItems 限制记录总数，但 concurrency 仍然最多为 5。
         *
         * false/null：
         * 继续使用原来的 maxItems 限制。
         */
        Boolean processAllItems,

        /**
         * 缺值跳过策略。
         *
         * 不配置时保持原有逻辑，保证旧工作流兼容。
         */
        ForEachMissingValueSkip missingValueSkip,

        /**
         * 每条记录执行的子图。
         */
        GraphSpec body) {
}