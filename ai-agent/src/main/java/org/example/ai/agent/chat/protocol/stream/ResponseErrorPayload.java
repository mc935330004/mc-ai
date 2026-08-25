package org.example.ai.agent.chat.protocol.stream;

import org.example.ai.agent.chat.protocol.response.ResponseDocument;

/**
 * 一次回答整体失败时发送的数据。
 *
 * snapshot允许携带已经成功生成的部分内容，
 * 避免整体异常导致业务数据全部消失。
 */
public record ResponseErrorPayload(
        String errorCode,
        String errorMessage,
        boolean retryable,
        ResponseDocument snapshot) {

    public ResponseErrorPayload {
        errorCode = StreamSupport.requireText(
                errorCode,
                "回答错误编码不能为空"
        );

        errorMessage = StreamSupport.normalizeText(errorMessage);
    }
}