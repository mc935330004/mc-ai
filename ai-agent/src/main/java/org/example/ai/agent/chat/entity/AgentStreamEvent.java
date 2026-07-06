package org.example.ai.agent.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话事件
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AgentStreamEvent {

    /**
     * 会话id
     */
    private String runId;

    /**
     * THINKING 思考中 , ANSWER 回答 , REFERENCES 引用 , DONE 完成 , ERROR 错误
     */
    private String type;

    /**
     * 内容
     */
    private String content;

    /**
     * 数据
     */
    private Object data;

    public static AgentStreamEvent of(String runId, String type, String content, Object data) {
        return new AgentStreamEvent(runId, type, content, data);
    }
}