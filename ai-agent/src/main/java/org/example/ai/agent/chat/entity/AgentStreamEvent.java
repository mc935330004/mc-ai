package org.example.ai.agent.chat.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent SSE 统一事件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStreamEvent {

    /**
     * Agent 本次运行 ID。
     */
    private String runId;

    /**
     * 本次回答消息 ID。
     *
     * 一个 runId 对应一个 messageId，
     * 前端使用 messageId 累加同一条 AI 回答。
     */
    private String messageId;

    /**
     * SSE 事件唯一 ID。
     *
     * 示例：
     * runId-1、runId-2。
     */
    private String eventId;

    /**
     * 事件序号。
     *
     * 同一个 runId 内从 1 开始单调递增。
     */
    private Long sequence;

    /**
     * 事件类型。
     */
    private String type;

    /**
     * 文本内容。
     */
    private String content;

    /**
     * 结构化数据。
     */
    private Object data;

    /**
     * 模型或回答结束原因。
     *
     * 示例：
     * STOP、LENGTH、ERROR、TIMEOUT。
     */
    private String finishReason;

    /**
     * 最终 Markdown 字符长度。
     */
    private Integer contentLength;

    /**
     * 最终 Markdown SHA-256。
     */
    private String contentHash;

    /**
     * 事件产生时间戳。
     */
    private Long timestamp;

    /**
     * 兼容原有代码的快速创建方法。
     *
     * messageId、sequence 等字段由 AgentStreamSession 统一补充。
     */
    public static AgentStreamEvent of( String runId,String type,String content, Object data) {
        return AgentStreamEvent.builder()
                .runId(runId)
                .type(type)
                .content(content)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}