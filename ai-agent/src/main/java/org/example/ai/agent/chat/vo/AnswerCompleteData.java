package org.example.ai.agent.chat.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 最终回答完成信息。
 */
@Data
@Builder
public class AnswerCompleteData {

    /**
     * SSE 协议版本。
     */
    private int protocolVersion;

    /**
     * 最终 Markdown 字符长度。
     */
    private int contentLength;

    /**
     * 最终 Markdown SHA-256。
     */
    private String contentHash;

    /**
     * 最终回答结束原因。
     */
    private String finishReason;
}