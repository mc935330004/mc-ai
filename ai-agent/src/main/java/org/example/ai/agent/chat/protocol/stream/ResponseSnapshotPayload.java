package org.example.ai.agent.chat.protocol.stream;

import org.example.ai.agent.chat.protocol.response.ResponseDocument;

import java.util.Objects;

/**
 * 当前回答的完整快照。
 *
 * 用于页面刷新、断线恢复、
 * 事件遗漏校验和跨页面继续展示。
 */
public record ResponseSnapshotPayload(
        ResponseDocument document,
        long lastSequence,
        String checksum) {

    public ResponseSnapshotPayload {
        document = Objects.requireNonNull(
                document,
                "回答快照document不能为空"
        );

        lastSequence = StreamSupport.normalizeSequence(lastSequence);

        checksum = StreamSupport.requireText(
                checksum,
                "回答快照checksum不能为空"
        );
    }
}