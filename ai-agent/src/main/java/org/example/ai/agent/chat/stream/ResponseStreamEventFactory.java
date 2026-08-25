package org.example.ai.agent.chat.stream;

import org.example.ai.agent.chat.protocol.block.ResponseBlock;
import org.example.ai.agent.chat.protocol.response.ResponseDocument;
import org.example.ai.agent.chat.protocol.stream.*;
import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;
import org.example.ai.agent.common.enums.protocol.ResponseStreamEventType;
import org.example.ai.agent.vo.ActionFormVO;
import org.example.ai.agent.vo.ActionPreviewVO;
import java.time.Instant;
import java.util.Objects;

/**
 * 单次AI回答的统一SSE事件创建器。
 *
 * 每次回答创建一个实例，
 * 统一控制事件序号、事件标识和快照校验值。
 */
public final class ResponseStreamEventFactory {

    private final ResponseStreamContext context;
    private final ResponseSequenceGenerator sequenceGenerator;
    private final ResponseChecksumService checksumService;

    /**
     * 创建新的回答事件流。
     */
    public ResponseStreamEventFactory(ResponseStreamContext context, ResponseChecksumService checksumService) {
        this(context, checksumService, 0);
    }

    /**
     * 从已经持久化的事件序号恢复回答事件流。
     */
    public ResponseStreamEventFactory(ResponseStreamContext context, ResponseChecksumService checksumService, long lastSequence) {

        this.context = Objects.requireNonNull(context, "回答流context不能为空");

        this.checksumService = Objects.requireNonNull(checksumService, "回答checksumService不能为空");

        this.sequenceGenerator = new ResponseSequenceGenerator(
                lastSequence
        );
    }

    /**
     * 创建回答开始事件。
     */
    public ResponseStreamEvent<ResponseStartPayload> responseStart(PresentationMode mode, boolean resumable) {
        ResponseStartPayload payload = new ResponseStartPayload(mode, ResponseStatus.RUNNING, resumable);
        return create(ResponseStreamEventType.RESPONSE_START, payload);
    }

    /**
     * 创建区块开始事件。
     */
    public ResponseStreamEvent<BlockStartPayload> blockStart(
            BlockStartPayload payload) {

        return create(
                ResponseStreamEventType.BLOCK_START,
                payload
        );
    }

    /**
     * 创建TEXT区块增量事件。
     */
    public ResponseStreamEvent<BlockDeltaPayload> blockDelta(BlockDeltaPayload payload) {
        return create(ResponseStreamEventType.BLOCK_DELTA, payload);
    }

    /**
     * 创建区块完成事件。
     */
    public ResponseStreamEvent<BlockDonePayload> blockDone(
            ResponseBlock block) {

        return create(
                ResponseStreamEventType.BLOCK_DONE,
                new BlockDonePayload(block)
        );
    }

    /**
     * 创建区块失败事件。
     */
    public ResponseStreamEvent<BlockErrorPayload> blockError(BlockErrorPayload payload) {

        return create(
                ResponseStreamEventType.BLOCK_ERROR,
                payload
        );
    }
    /**
     * 创建写操作参数表单事件。
     */
    public ResponseStreamEvent<ActionFormVO> actionForm(ActionFormVO form) {
        return create(ResponseStreamEventType.ACTION_FORM, Objects.requireNonNull(form, "写操作表单不能为空"));
    }

    /**
     * 创建写操作确认预览事件。
     */
    public ResponseStreamEvent<ActionPreviewVO> actionPreview(ActionPreviewVO preview) {
        return create(ResponseStreamEventType.ACTION_PREVIEW, Objects.requireNonNull(preview, "写操作确认预览不能为空"));
    }
    /**
     * 创建完整回答快照事件。
     */
    public ResponseStreamEvent<ResponseSnapshotPayload> responseSnapshot(ResponseDocument document) {
        long sequence = sequenceGenerator.next();
        String checksum = checksumService.calculate(document);
        ResponseSnapshotPayload payload = new ResponseSnapshotPayload(document, sequence, checksum);
        return createWithSequence(sequence, ResponseStreamEventType.RESPONSE_SNAPSHOT, payload);
    }

    /**
     * 创建回答正常结束事件。
     */
    public ResponseStreamEvent<ResponseDonePayload> responseDone(
            ResponseDocument document) {

        long sequence = sequenceGenerator.next();
        String checksum = checksumService.calculate(document);

        ResponseDonePayload payload = new ResponseDonePayload(
                document,
                sequence,
                checksum
        );

        return createWithSequence(
                sequence,
                ResponseStreamEventType.RESPONSE_DONE,
                payload
        );
    }

    /**
     * 创建回答整体失败事件。
     *
     * snapshot允许为空，
     * 有部分成功内容时应尽量传入当前回答快照。
     */
    public ResponseStreamEvent<ResponseErrorPayload> responseError(
            String errorCode,
            String errorMessage,
            boolean retryable,
            ResponseDocument snapshot) {

        ResponseErrorPayload payload = new ResponseErrorPayload(
                errorCode,
                errorMessage,
                retryable,
                snapshot
        );

        return create(
                ResponseStreamEventType.RESPONSE_ERROR,
                payload
        );
    }

    /**
     * 创建SSE连接心跳事件。
     */
    public ResponseStreamEvent<HeartbeatPayload> heartbeat() {
        long sequence = sequenceGenerator.next();

        HeartbeatPayload payload = new HeartbeatPayload(
                sequence
        );

        return createWithSequence(
                sequence,
                ResponseStreamEventType.HEARTBEAT,
                payload
        );
    }

    /**
     * 获取当前已经分配的最后事件序号。
     */
    public long currentSequence() {
        return sequenceGenerator.current();
    }

    /**
     * 创建普通事件并自动分配序号。
     */
    private <T> ResponseStreamEvent<T> create(
            ResponseStreamEventType eventType,
            T payload) {

        long sequence = sequenceGenerator.next();

        return createWithSequence(
                sequence,
                eventType,
                payload
        );
    }

    /**
     * 使用已经分配的序号创建事件。
     */
    private <T> ResponseStreamEvent<T> createWithSequence(
            long sequence,
            ResponseStreamEventType eventType,
            T payload) {

        String eventId = context.responseId()
                + ":"
                + sequence;

        return new ResponseStreamEvent<>(
                ResponseStreamEvent.CURRENT_SCHEMA_VERSION,
                eventId,
                context.responseId(),
                context.runId(),
                context.conversationId(),
                sequence,
                eventType,
                Instant.now(),
                payload
        );
    }
}