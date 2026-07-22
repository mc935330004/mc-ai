package org.example.ai.agent.graph.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * FOREACH 单条记录缺少必要值时的跳过策略。
 *
 * 示例：
 *
 * {
 *   "expression": "$item.id",
 *   "code": "SKIPPED_NO_ID",
 *   "message": "列表记录缺少 id，未调用详情接口"
 * }
 *
 * 该配置属于通用工作流能力，不与 PM 系统或某个具体接口绑定。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ForEachMissingValueSkip(

        /**
         * 需要检查的表达式。
         *
         * 当表达式计算结果为 null、空字符串或纯空格时，
         * 当前记录不会执行 FOREACH 子图。
         */
        String expression,

        /**
         * 跳过原因编码。
         */
        String code,

        /**
         * 可安全展示和持久化的跳过原因。
         *
         * 不允许在这里写入 Token、接口地址或敏感请求数据。
         */
        String message) {
}