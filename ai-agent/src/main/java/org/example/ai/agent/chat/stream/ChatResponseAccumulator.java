package org.example.ai.agent.chat.stream;

import org.example.ai.agent.chat.protocol.block.ResponseBlock;
import org.example.ai.agent.chat.protocol.block.TextBlock;
import org.example.ai.agent.chat.protocol.response.AiResponse;
import org.example.ai.agent.chat.protocol.response.ResponseDocument;
import org.example.ai.agent.chat.protocol.response.ResponseMeta;
import org.example.ai.agent.chat.protocol.response.ResponseReference;
import org.example.ai.agent.chat.protocol.stream.BlockDeltaPayload;
import org.example.ai.agent.chat.protocol.stream.BlockErrorPayload;
import org.example.ai.agent.chat.protocol.stream.BlockStartPayload;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * CHAT模式回答内容累计器。
 *
 * 负责累计TEXT增量和完整结构化区块，
 * 并随时生成当前完整回答快照。
 */
public class ChatResponseAccumulator {

    private final ResponseStreamContext context;

    /**
     * 已经完整生成的区块。
     */
    private final Map<String, ResponseBlock> completedBlocks = new LinkedHashMap<>();

    /**
     * 正在流式生成的TEXT区块信息。
     */
    private final Map<String, BlockStartPayload> streamingTextBlocks = new LinkedHashMap<>();

    /**
     * 正在累计的TEXT内容。
     */
    private final Map<String, StringBuilder> streamingTextContents = new LinkedHashMap<>();

    private List<ResponseReference> references = List.of();
    private ResponseMeta meta = ResponseMeta.empty();
    private ResponseStatus status = ResponseStatus.RUNNING;
    private boolean dataComplete;
    /**
     * 记录区块失败，整体状态在回答结束时统一确定。
     */
    private boolean blockFailed;
    public ChatResponseAccumulator(ResponseStreamContext context) {
        this.context = Objects.requireNonNull(
                context,
                "CHAT回答context不能为空"
        );
    }

    /**
     * 开始一个流式TEXT区块。
     */
    public synchronized void startText(BlockStartPayload payload) {
        Objects.requireNonNull(
                payload,
                "TEXT区块开始数据不能为空"
        );

        if (payload.blockType() != BlockType.TEXT) {
            throw new IllegalArgumentException(
                    "只有TEXT区块允许流式增量输出"
            );
        }

        if (completedBlocks.containsKey(payload.blockId())) {
            return;
        }

        streamingTextBlocks.putIfAbsent(
                payload.blockId(),
                payload
        );

        streamingTextContents.putIfAbsent(
                payload.blockId(),
                new StringBuilder()
        );
    }

    /**
     * 追加TEXT增量内容。
     */
    public synchronized void appendText(BlockDeltaPayload payload) {
        Objects.requireNonNull(
                payload,
                "TEXT增量数据不能为空"
        );

        StringBuilder content = streamingTextContents.get(
                payload.blockId()
        );

        if (content == null) {
            throw new IllegalStateException(
                    "TEXT区块尚未开始，blockId=" + payload.blockId()
            );
        }

        content.append(payload.delta());
    }

    /**
     * 保存一个完整区块。
     *
     * TEXT和结构化区块完成后都调用该方法。
     */
    public synchronized void completeBlock(ResponseBlock block) {
        Objects.requireNonNull(
                block,
                "完成的区块不能为空"
        );

        streamingTextBlocks.remove(block.id());
        streamingTextContents.remove(block.id());
        completedBlocks.put(block.id(), block);
    }

    /**
     * 标记单个区块失败。
     *
     * TEXT区块已经生成的部分内容仍然保留，
     * 不清除其他成功业务区块。
     */
    public synchronized void failBlock(BlockErrorPayload payload) {
        Objects.requireNonNull(
                payload,
                "区块失败数据不能为空"
        );

        BlockStartPayload start = streamingTextBlocks.remove(
                payload.blockId()
        );

        StringBuilder content = streamingTextContents.remove(
                payload.blockId()
        );

        if (start != null && start.blockType() == BlockType.TEXT) {
            TextBlock failedText = new TextBlock(
                    start.blockId(),
                    start.title(),
                    start.order(),
                    BlockStatus.FAILED,
                    start.source(),
                    content == null ? "" : content.toString()
            );
            completedBlocks.put(failedText.id(), failedText);
        }
        // 单个区块失败不代表整个回答已经结束。
        blockFailed = true;
    }

    /**
     * 更新业务基础数据是否完整。
     */
    public synchronized void setDataComplete(boolean dataComplete) {
        this.dataComplete = dataComplete;
    }

    /**
     * 更新知识库引用。
     */
    public synchronized void setReferences(List<ResponseReference> references) {

        this.references = references == null ? List.of() : List.copyOf(references);
    }

    /**
     * 更新本次回答运行信息。
     */
    public synchronized void setMeta(ResponseMeta meta) {
        this.meta = meta == null ? ResponseMeta.empty() : meta;
    }

    /**
     * 生成当前回答快照。
     *
     * 正在流式生成的TEXT内容也会出现在快照中。
     */
    public synchronized AiResponse snapshot() {
        return new AiResponse(
                ResponseDocument.CURRENT_SCHEMA_VERSION,
                context.responseId(),
                context.runId(),
                context.conversationId(),
                PresentationMode.CHAT,
                status,
                dataComplete,
                currentBlocks(),
                references,
                meta
        );
    }

    /**
     * 结束回答，并根据数据完整性和区块状态确定最终结果。
     */
    public synchronized AiResponse complete() {
        if (status != ResponseStatus.RUNNING) {
            return snapshot();
        }

        boolean incomplete = !dataComplete
                || blockFailed
                || !streamingTextBlocks.isEmpty();

        // 没有正常结束的文字不能被标记为生成成功。
        finishStreamingText(BlockStatus.FAILED);

        for (ResponseBlock block : completedBlocks.values()) {
            if (block.status() != BlockStatus.READY) {
                incomplete = true;
            }
        }

        if (!hasVisibleContent()) {
            status = ResponseStatus.FAILED;
        } else {
            status = incomplete
                    ? ResponseStatus.PARTIAL
                    : ResponseStatus.COMPLETED;
        }

        return snapshot();
    }

    /**
     * 整体失败时保留成功区块和已经生成的文字。
     */
    public synchronized AiResponse fail() {
        finishStreamingText(BlockStatus.FAILED);
        status = hasVisibleContent()
                ? ResponseStatus.PARTIAL
                : ResponseStatus.FAILED;
        return snapshot();
    }

    /**
     * 主动取消只终止正在生成的文字，不修改已经完成的业务区块。
     */
    public synchronized AiResponse cancel() {
        finishStreamingText(BlockStatus.CANCELLED);
        status = ResponseStatus.CANCELLED;
        return snapshot();
    }

    /**
     * 判断是否存在可展示内容，失败文字中的已有内容同样保留。
     */
    private boolean hasVisibleContent() {
        for (ResponseBlock block : completedBlocks.values()) {
            if (block instanceof TextBlock text) {
                if (text.markdown() != null && !text.markdown().isBlank()) {
                    return true;
                }
            } else if (block.status() == BlockStatus.READY) {
                return true;
            }
        }

        for (StringBuilder content : streamingTextContents.values()) {
            if (!content.toString().isBlank()) {
                return true;
            }
        }

        return false;
    }

    /**
     * 组装当前全部区块。
     */
    private List<ResponseBlock> currentBlocks() {
        List<ResponseBlock> blocks = new ArrayList<>(completedBlocks.values());

        for (Map.Entry<String, BlockStartPayload> entry : streamingTextBlocks.entrySet()) {

            BlockStartPayload start = entry.getValue();
            StringBuilder content = streamingTextContents.get(entry.getKey());

            blocks.add(new TextBlock(start.blockId(), start.title(), start.order(),
                    BlockStatus.STREAMING, start.source(), content == null ? "" : content.toString()));
        }

        blocks.sort(Comparator.comparingInt(ResponseBlock::order).thenComparing(ResponseBlock::id));

        return List.copyOf(blocks);
    }
    /**
     * 将正在生成的文字固定为终态，内容原样保留。
     */
    private void finishStreamingText(BlockStatus finalStatus) {
        for (Map.Entry<String, BlockStartPayload> entry
                : streamingTextBlocks.entrySet()) {
            BlockStartPayload start = entry.getValue();
            StringBuilder content = streamingTextContents.get(entry.getKey());

            TextBlock text = new TextBlock(
                    start.blockId(),
                    start.title(),
                    start.order(),
                    finalStatus,
                    start.source(),
                    content == null ? "" : content.toString()
            );
            completedBlocks.put(text.id(), text);
        }

        streamingTextBlocks.clear();
        streamingTextContents.clear();
    }
}