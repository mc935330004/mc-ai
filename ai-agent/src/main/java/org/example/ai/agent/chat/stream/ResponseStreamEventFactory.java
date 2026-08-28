package org.example.ai.agent.chat.stream;

import org.example.ai.agent.chat.protocol.block.ResponseBlock;
import org.example.ai.agent.chat.protocol.response.ResponseDocument;
import org.example.ai.agent.chat.protocol.stream.BlockDeltaPayload;
import org.example.ai.agent.chat.protocol.stream.BlockDonePayload;
import org.example.ai.agent.chat.protocol.stream.BlockErrorPayload;
import org.example.ai.agent.chat.protocol.stream.BlockStartPayload;
import org.example.ai.agent.chat.protocol.stream.HeartbeatPayload;
import org.example.ai.agent.chat.protocol.stream.ResponseDonePayload;
import org.example.ai.agent.chat.protocol.stream.ResponseErrorPayload;
import org.example.ai.agent.chat.protocol.stream.ResponseSnapshotPayload;
import org.example.ai.agent.chat.protocol.stream.ResponseStartPayload;
import org.example.ai.agent.chat.protocol.stream.ResponseStreamEvent;
import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;
import org.example.ai.agent.common.enums.protocol.ResponseStreamEventType;
import org.example.ai.agent.vo.ActionFormVO;
import org.example.ai.agent.vo.ActionPreviewVO;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次AI回答的统一SSE事件创建器。
 *
 * 一个回答对应一个实例。
 * 所有事件共用一套sequence。
 * 每个Block拥有独立的revision。
 */
public final class ResponseStreamEventFactory {

    private final ResponseStreamContext context;
    private final ResponseSequenceGenerator sequenceGenerator;
    private final ResponseChecksumService checksumService;

    /**
     * 保存每个Block当前的revision。
     *
     * 可能存在AI分析和业务Block并行发送，
     * 因此使用线程安全集合。
     */
    private final ConcurrentMap<String, AtomicLong> blockRevisions =
            new ConcurrentHashMap<>();

    /**
     * 复用当前SSE会话的序号生成器。
     * 进度事件和回答事件不能各自从1开始编号。
     */
    public ResponseStreamEventFactory(ResponseStreamContext context, ResponseChecksumService checksumService, ResponseSequenceGenerator sequenceGenerator) {

        this.context = Objects.requireNonNull(
                context,
                "回答流context不能为空"
        );
        this.checksumService = Objects.requireNonNull(
                checksumService,
                "回答checksumService不能为空"
        );
        this.sequenceGenerator = Objects.requireNonNull(
                sequenceGenerator,
                "回答流序号生成器不能为空"
        );
    }

    /**
     * 创建回答开始事件。
     */
    public ResponseStreamEvent<ResponseStartPayload> responseStart(
            PresentationMode mode,
            boolean resumable) {

        ResponseStartPayload payload =
                new ResponseStartPayload(
                        mode,
                        ResponseStatus.RUNNING,
                        resumable
                );

        return createResponseEvent(
                ResponseStreamEventType.RESPONSE_START,
                payload
        );
    }

    /**
     * 创建Block开始事件。
     */
    public ResponseStreamEvent<BlockStartPayload> blockStart(
            BlockStartPayload payload) {

        Objects.requireNonNull(
                payload,
                "Block开始数据不能为空"
        );

        long revision =
                currentBlockRevision(
                        payload.blockId()
                );

        return createBlockEvent(
                payload.blockId(),
                revision,
                ResponseStreamEventType.BLOCK_START,
                payload
        );
    }

    /**
     * 创建TEXT Block增量事件。
     */
    public ResponseStreamEvent<BlockDeltaPayload> blockDelta(
            BlockDeltaPayload payload) {

        Objects.requireNonNull(
                payload,
                "Block增量数据不能为空"
        );

        long revision =
                nextBlockRevision(
                        payload.blockId()
                );

        return createBlockEvent(
                payload.blockId(),
                revision,
                ResponseStreamEventType.BLOCK_DELTA,
                payload
        );
    }

    /**
     * 创建Block完成事件。
     *
     * 结构化Block通过该事件一次返回完整内容。
     */
    public ResponseStreamEvent<BlockDonePayload> blockDone(
            ResponseBlock block) {

        Objects.requireNonNull(
                block,
                "完成的Block不能为空"
        );

        long revision =
                nextBlockRevision(
                        block.id()
                );

        return createBlockEvent(
                block.id(),
                revision,
                ResponseStreamEventType.BLOCK_DONE,
                new BlockDonePayload(block)
        );
    }

    /**
     * 创建Block失败事件。
     */
    public ResponseStreamEvent<BlockErrorPayload> blockError(
            BlockErrorPayload payload) {

        Objects.requireNonNull(
                payload,
                "Block错误数据不能为空"
        );

        long revision =
                nextBlockRevision(
                        payload.blockId()
                );

        return createBlockEvent(
                payload.blockId(),
                revision,
                ResponseStreamEventType.BLOCK_ERROR,
                payload
        );
    }

    /**
     * 创建写操作参数表单事件。
     *
     * 写操作继续使用独立业务对象，
     * 但复用统一SSE信封。
     */
    public ResponseStreamEvent<ActionFormVO> actionForm(
            ActionFormVO form) {

        return createResponseEvent(
                ResponseStreamEventType.ACTION_FORM,
                Objects.requireNonNull(
                        form,
                        "写操作表单不能为空"
                )
        );
    }

    /**
     * 创建写操作确认预览事件。
     */
    public ResponseStreamEvent<ActionPreviewVO> actionPreview(
            ActionPreviewVO preview) {

        return createResponseEvent(
                ResponseStreamEventType.ACTION_PREVIEW,
                Objects.requireNonNull(
                        preview,
                        "写操作确认预览不能为空"
                )
        );
    }

    /**
     * 创建完整回答快照事件。
     */
    public ResponseStreamEvent<ResponseSnapshotPayload> responseSnapshot(ResponseDocument document) {
        long sequence = sequenceGenerator.next();
        String documentJson = checksumService.serialize(document);

        ResponseSnapshotPayload payload = new ResponseSnapshotPayload(
                documentJson,
                sequence,
                checksumService.calculate(documentJson)
        );

        return createWithSequence(
                sequence,
                "",
                0,
                ResponseStreamEventType.RESPONSE_SNAPSHOT,
                payload
        );
    }

    /**
     * 创建回答完成事件，正文与校验值使用同一份JSON。
     */
    public ResponseStreamEvent<ResponseDonePayload> responseDone(ResponseDocument document) {

        long sequence = sequenceGenerator.next();
        String documentJson = checksumService.serialize(document);

        ResponseDonePayload payload = new ResponseDonePayload(
                documentJson,
                sequence,
                checksumService.calculate(documentJson)
        );

        return createWithSequence(
                sequence,
                "",
                0,
                ResponseStreamEventType.RESPONSE_DONE,
                payload
        );
    }

    /**
     * 创建回答整体失败事件。
     *
     * snapshot可以保存已经完成的Block，
     * 禁止因为AI分析失败清空业务数据。
     */
    public ResponseStreamEvent<ResponseErrorPayload> responseError(
            String errorCode,
            String errorMessage,
            boolean retryable,
            ResponseDocument snapshot) {

        ResponseErrorPayload payload =
                new ResponseErrorPayload(
                        errorCode,
                        errorMessage,
                        retryable,
                        snapshot
                );

        return createResponseEvent(
                ResponseStreamEventType.RESPONSE_ERROR,
                payload
        );
    }

    /**
     * 创建连接心跳事件。
     */
    public ResponseStreamEvent<HeartbeatPayload> heartbeat() {

        long sequence = sequenceGenerator.next();

        HeartbeatPayload payload =
                new HeartbeatPayload(
                        sequence
                );

        return createWithSequence(
                sequence,
                "",
                0,
                ResponseStreamEventType.HEARTBEAT,
                payload
        );
    }

    /**
     * 获取当前最后一个sequence。
     */
    public long currentSequence() {
        return sequenceGenerator.current();
    }

    /**
     * 获取Block当前revision。
     *
     * 第一次BLOCK_START固定返回0。
     */
    private long currentBlockRevision(String blockId) {

        return blockRevisions
                .computeIfAbsent(
                        requireBlockId(blockId),
                        key -> new AtomicLong(0)
                )
                .get();
    }

    /**
     * 获取Block的下一个revision。
     */
    private long nextBlockRevision(String blockId) {

        return blockRevisions
                .computeIfAbsent(
                        requireBlockId(blockId),
                        key -> new AtomicLong(0)
                )
                .incrementAndGet();
    }

    /**
     * 创建响应级事件。
     */
    private <T> ResponseStreamEvent<T> createResponseEvent(
            ResponseStreamEventType eventType,
            T payload) {

        long sequence = sequenceGenerator.next();

        return createWithSequence(
                sequence,
                "",
                0,
                eventType,
                payload
        );
    }

    /**
     * 创建Block级事件。
     */
    private <T> ResponseStreamEvent<T> createBlockEvent(
            String blockId,
            long revision,
            ResponseStreamEventType eventType,
            T payload) {

        long sequence = sequenceGenerator.next();

        return createWithSequence(
                sequence,
                requireBlockId(blockId),
                revision,
                eventType,
                payload
        );
    }

    /**
     * 使用指定sequence创建统一事件。
     */
    private <T> ResponseStreamEvent<T> createWithSequence(
            long sequence,
            String blockId,
            long revision,
            ResponseStreamEventType eventType,
            T payload) {

        String eventId =
                context.responseId()
                        + ":"
                        + sequence;

        return new ResponseStreamEvent<>(
                ResponseStreamEvent.CURRENT_SCHEMA_VERSION,
                eventId,
                context.responseId(),
                context.runId(),
                context.conversationId(),
                blockId,
                sequence,
                revision,
                eventType,
                Instant.now(),
                payload
        );
    }

    private String requireBlockId(String blockId) {

        if (blockId == null || blockId.isBlank()) {
            throw new IllegalArgumentException(
                    "Block事件blockId不能为空"
            );
        }

        return blockId.trim();
    }
}