package org.example.ai.agent.chat.protocol.stream;

import org.example.ai.agent.chat.protocol.block.ResponseBlock;

import java.util.Objects;

/**
 * 单个区块完整生成后的事件数据。
 *
 * TEXT区块发送最终完整内容，
 * 结构化区块必须通过该事件一次发送完整对象。
 */
public record BlockDonePayload(
        ResponseBlock block) {

    public BlockDonePayload {
        block = Objects.requireNonNull(
                block,
                "完成的区块数据不能为空"
        );
    }
}