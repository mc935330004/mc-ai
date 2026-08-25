package org.example.ai.agent.chat.protocol.stream;

import org.example.ai.agent.chat.protocol.response.ResponseDocument;

import java.util.Objects;

/**
 * 一次回答正常结束时发送的数据。
 *
 * document保存最终完整回答，
 * 防止前端因遗漏某个增量事件导致内容不完整。
 */
public record ResponseDonePayload(
        ResponseDocument document,
        long lastSequence,
        String checksum) {

    public ResponseDonePayload {
        document = Objects.requireNonNull(
                document,
                "最终回答document不能为空"
        );

        lastSequence = StreamSupport.normalizeSequence(lastSequence);

        checksum = StreamSupport.requireText(
                checksum,
                "最终回答checksum不能为空"
        );
    }
}