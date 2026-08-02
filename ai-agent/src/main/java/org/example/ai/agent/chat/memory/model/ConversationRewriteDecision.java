package org.example.ai.agent.chat.memory.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 中文注释：上下文语义判定模型的严格 JSON 返回结构。
 *
 * relation 只允许：
 * FOLLOW_UP、NEW_TOPIC、UNCERTAIN。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ConversationRewriteDecision(
        String relation,
        String rewrittenQuestion,
        Double confidence,
        String reason) {
}