package org.example.ai.agent.chat.protocol.block;

import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.example.ai.agent.common.enums.protocol.Tone;

/**
 * 需要重点展示的确定性结论。
 *
 * content使用普通文本，
 * 不在CALLOUT中生成Markdown页面。
 */
public record CalloutBlock(
        String id,
        String title,
        int order,
        BlockStatus status,
        BlockSource source,
        Tone tone,
        String content)
        implements ResponseBlock {

    public CalloutBlock {
        id = BlockSupport.requireId(id);
        title = BlockSupport.normalizeTitle(title);
        order = BlockSupport.normalizeOrder(order);
        status = BlockSupport.normalizeStatus(status);
        source = BlockSupport.normalizeSource(source);
        tone = tone == null ? Tone.INFO : tone;
        content = content == null ? "" : content.trim();
    }

    @Override
    public BlockType type() {
        return BlockType.CALLOUT;
    }
}