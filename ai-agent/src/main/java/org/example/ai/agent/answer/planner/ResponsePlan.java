package org.example.ai.agent.answer.planner;

import org.example.ai.agent.chat.protocol.block.ResponseBlock;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 确定性回答规划结果。
 *
 * 只保存后端根据真实事实生成的业务区块，
 * AI自然语言区块由后续流式回答阶段追加。
 */
public record ResponsePlan(
        List<ResponseBlock> blocks,
        boolean narrativeRequired,
        boolean dataComplete,
        long totalCount) {

    public ResponsePlan {
        blocks = blocks == null
                ? List.of()
                : blocks.stream()
                .filter(Objects::nonNull)
                .sorted(
                        Comparator.comparingInt(
                                ResponseBlock::order
                        )
                )
                .toList();

        totalCount = Math.max(totalCount, 0);
    }
}