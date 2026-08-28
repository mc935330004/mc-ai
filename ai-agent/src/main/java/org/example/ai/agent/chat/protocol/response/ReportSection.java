package org.example.ai.agent.chat.protocol.response;

import org.example.ai.agent.chat.protocol.block.ResponseBlock;

import java.util.List;

/**
 * 完整报告中的一个章节。
 *
 * 章节只负责组织Block，不定义业务专属页面结构。
 * 所有报告最终都由reportDefinition配置生成。
 */
public record ReportSection(String id, String title, int order, List<ResponseBlock> blocks) {

    public ReportSection {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "报告章节id不能为空"
            );
        }

        id = id.trim();
        title = title == null ? "" : title.trim();
        order = Math.max(order, 0);
        blocks = blocks == null
                ? List.of()
                : List.copyOf(blocks);
    }
}