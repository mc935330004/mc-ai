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
        status = ResponseStatus.PARTIAL;
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
     * 正常完成回答。
     *
     * 已经出现部分失败时保留PARTIAL状态。
     */
    public synchronized AiResponse complete() {
        if (status == ResponseStatus.RUNNING) {
            status = ResponseStatus.COMPLETED;
        }

        return snapshot();
    }

    /**
     * 整体处理失败。
     *
     * 已经有可展示内容时返回PARTIAL，
     * 完全没有内容时返回FAILED。
     */
    public synchronized AiResponse fail() {
        status = hasVisibleContent() ? ResponseStatus.PARTIAL : ResponseStatus.FAILED;
        return snapshot();
    }

    /**
     * 用户主动终止回答。
     */
    public synchronized AiResponse cancel() {
        status = ResponseStatus.CANCELLED;
        return snapshot();
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
     * 判断当前是否已有用户可以看到的内容。
     */
    private boolean hasVisibleContent() {
        for (ResponseBlock block : completedBlocks.values()) {
            if (block.status() != BlockStatus.FAILED) {
                return true;
            }
        }

        for (StringBuilder content : streamingTextContents.values()) {
            if (!content.isEmpty()) {
                return true;
            }
        }

        return false;
    }
}