package org.example.ai.agent.chat.memory.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 会话上下文语义判断结果。
 *
 * 模型只能判断当前问题与上一轮的关系，
 * 不能决定数据库快照ID，也不能直接调用业务系统。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ConversationRewriteDecision(

        /**
         * 只允许：
         * RESULT_ANALYSIS
         * FOLLOW_UP_QUERY
         * NEW_TOPIC
         * UNCERTAIN
         */
        String relation,

        /**
         * 改写后的独立完整问题。
         */
        String rewrittenQuestion,

        /**
         * 判断置信度，范围0～1。
         */
        Double confidence,

        /**
         * 安全的简短判断原因。
         */
        String reason) {
}