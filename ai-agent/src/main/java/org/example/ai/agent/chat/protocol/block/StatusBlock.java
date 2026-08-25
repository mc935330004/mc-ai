package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.example.ai.agent.common.enums.protocol.Tone;

/**
 * 审批状态、执行状态等确定性状态区块。
 */
public record StatusBlock(
        String id,
        String title,
        int order,
        BlockStatus status,
        BlockSource source,
        String code,
        String label,
        Tone tone)
        implements ResponseBlock {

    public StatusBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);

        code = code == null ? "" : code.trim();
        label = label == null ? "" : label.trim();
        tone = tone == null ? Tone.DEFAULT : tone;
    }

    @Override
    public BlockType type() {
        return BlockType.STATUS;
    }
}