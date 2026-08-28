package org.example.ai.agent.chat.protocol.stream;

/**
 * 当前回答的完整快照。
 * documentJson与checksum必须对应同一份原始文本。
 */
public record ResponseSnapshotPayload(String documentJson, long lastSequence, String checksum) {
    public ResponseSnapshotPayload {
        if (documentJson == null || documentJson.isBlank()) {
            throw new IllegalArgumentException("回答快照JSON不能为空");
        }

        // 不对JSON执行trim或格式化，避免改变校验内容。
        lastSequence = StreamSupport.normalizeSequence(lastSequence);
        checksum = StreamSupport.requireText(
                checksum,
                "回答快照checksum不能为空"
        );
    }
}