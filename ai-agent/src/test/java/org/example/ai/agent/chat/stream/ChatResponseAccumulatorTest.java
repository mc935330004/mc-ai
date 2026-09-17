package org.example.ai.agent.chat.stream;

import org.example.ai.agent.chat.protocol.block.TextBlock;
import org.example.ai.agent.chat.protocol.stream.BlockDeltaPayload;
import org.example.ai.agent.chat.protocol.stream.BlockStartPayload;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatResponseAccumulatorTest {

    @Test
    void completedResponseRejectsLateBlockAndKeepsFrozenSnapshot() {
        ChatResponseAccumulator accumulator = accumulator();
        accumulator.setDataComplete(true);
        accumulator.completeBlock(text("answer", "最终回答"));

        var completed = accumulator.complete();

        assertThatThrownBy(() -> accumulator.completeBlock(text("late", "迟到结果")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAT回答已经结束");
        assertThat(accumulator.cancel()).isEqualTo(completed);
        assertThat(accumulator.snapshot()).isEqualTo(completed);
    }

    @Test
    void cancelledResponseRejectsLateTextDeltaAndKeepsCancelledSnapshot() {
        ChatResponseAccumulator accumulator = accumulator();
        accumulator.startText(new BlockStartPayload(
                "answer", BlockType.TEXT, "回答", 0, BlockSource.AI
        ));
        accumulator.appendText(new BlockDeltaPayload("answer", 1, "已有内容"));

        var cancelled = accumulator.cancel();

        assertThatThrownBy(() -> accumulator.appendText(
                new BlockDeltaPayload("answer", 2, "迟到内容")
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("CHAT回答已经结束");
        assertThat(accumulator.fail()).isEqualTo(cancelled);
        assertThat(accumulator.snapshot()).isEqualTo(cancelled);
    }

    private ChatResponseAccumulator accumulator() {
        return new ChatResponseAccumulator(
                new ResponseStreamContext("response-1", "run-1", "conversation-1")
        );
    }

    private TextBlock text(String id, String markdown) {
        return new TextBlock(id, "", 0, BlockStatus.READY, BlockSource.SYSTEM, markdown);
    }
}
