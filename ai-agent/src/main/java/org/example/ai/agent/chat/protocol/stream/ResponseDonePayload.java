package org.example.ai.agent.chat.protocol.stream;

/**
 * 回答结束时发送完整快照，修复前端可能遗漏的增量内容。
 */
public record ResponseDonePayload(String documentJson, long lastSequence, String checksum) {
    public ResponseDonePayload {
        if (documentJson == null || documentJson.isBlank()) {
            throw new IllegalArgumentException("最终回答JSON不能为空");
        }

        // 保留原始JSON文本，不在协议层重新格式化。
        lastSequence = StreamSupport.normalizeSequence(lastSequence);
        checksum = StreamSupport.requireText(
                checksum,
                "最终回答checksum不能为空"
        );
    }
}