package org.example.ai.agent.chat.vo;

import org.example.ai.agent.chat.protocol.block.TableBlock;

import java.util.List;

/**
 * 表格分页结果，仅用于页面展示，不修改原始回答快照。
 */
public record ChatTablePageVO(
        String responseId,
        String blockId,
        int current,
        int size,
        long total,
        long sourceTotal,
        boolean dataComplete,
        List<TableBlock.Row> records) {
}