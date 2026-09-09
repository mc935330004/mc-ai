package org.example.ai.agent.chat.support;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentStreamEvent;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.observability.AgentMetrics;
import org.flywaydb.core.internal.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.example.ai.agent.chat.stream.ChatResponseAccumulator;
import org.example.ai.agent.chat.stream.ResponseChecksumService;
import org.example.ai.agent.chat.stream.ResponseStreamContext;
import org.example.ai.agent.chat.protocol.stream.ResponseStreamEvent;
import org.example.ai.agent.chat.stream.ResponseStreamEventFactory;
import org.example.ai.agent.common.enums.protocol.ResponseStreamEventType;
import org.example.ai.agent.chat.protocol.block.ResponseBlock;
import org.example.ai.agent.chat.protocol.block.TextBlock;
import org.example.ai.agent.chat.protocol.response.AiResponse;
import org.example.ai.agent.chat.protocol.response.ResponseMeta;
import org.example.ai.agent.chat.protocol.response.ResponseReference;
import org.example.ai.agent.chat.protocol.stream.BlockDeltaPayload;
import org.example.ai.agent.chat.protocol.stream.BlockErrorPayload;
import org.example.ai.agent.chat.protocol.stream.BlockStartPayload;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.chat.protocol.response.ReportSchema;
import org.example.ai.agent.chat.protocol.response.ResponseDocument;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.example.ai.agent.chat.stream.ResponseSequenceGenerator;
/**
 * 单次 Agent SSE 会话。
 *
 * 每个聊天请求创建一个实例，
 * 统一管理 sequence、messageId、快照和连接关闭。
 */
@Slf4j
public class AgentStreamSession {
    /**
     * 当前统一响应的展示模式。
     *
     * 一次SSE连接只能发送一种顶层响应，
     * 禁止CHAT和REPORT在同一个responseId中混用。
     */
    private PresentationMode responseMode;
    /**
     * 保存本次已准备的报告，异常收尾时保留报告模式和业务区块。
     */
    @Getter
    private volatile ReportSchema currentReport;
    /**
     * -- GETTER --
     *  Controller 最终需要返回底层 SseEmitter。
     */
    @Getter
    private final SseEmitter emitter;
    private final AgentMetrics agentMetrics;
    private boolean responseStarted;
    private BlockStartPayload activeTextBlock;
    private long activeTextDeltaIndex;
    private final AtomicBoolean firstContentRecorded =  new AtomicBoolean(false);

    @Getter
    private final String runId;

    @Getter
    private final String messageId;
    /**
     * 当前聊天会话标识。
     */
    @Getter
    private final String conversationId;

    /**
     * 新版SSE事件创建器。
     */
    @Getter
    private final ResponseStreamEventFactory responseEventFactory;

    /**
     * CHAT回答内容累计器。
     */
    @Getter
    private final ChatResponseAccumulator chatResponseAccumulator;
    /**
     * 单次请求唯一的事件序号生成器。
     * 进度事件与统一回答事件共同使用。
     */
    private final ResponseSequenceGenerator sequenceGenerator = new ResponseSequenceGenerator();
    /**
     * SSE会话创建时间。
     */
    private final long startedAt = System.currentTimeMillis();
    private final AtomicBoolean completed = new AtomicBoolean(false);
    /**
     * 只表示网络连接是否可用，不代表后台回答已经结束。
     */
    private final AtomicBoolean connectionOpen = new AtomicBoolean(true);

    /**
     * 用户取消意图独立保存，避免线程中断标志被底层组件清除后丢失。
     */
    @Getter
    private volatile boolean cancellationRequested;

    /**
     * 进入最终保存后，不再接受新的取消请求。
     */
    private boolean finalizing;

    /**
     * 当前聊天任务的执行线程，排队时为空。
     */
    private Thread executionThread;
    /**
     * 创建统一协议SSE会话。
     */
    public AgentStreamSession(
            SseEmitter emitter,
            String runId,
            String conversationId,
            AgentMetrics agentMetrics,
            ResponseChecksumService checksumService) {

        this.emitter = Objects.requireNonNull(
                emitter,
                "SseEmitter不能为空"
        );
        this.runId = requireText(runId, "runId不能为空");
        this.conversationId = requireText(
                conversationId,
                "conversationId不能为空"
        );
        this.agentMetrics = Objects.requireNonNull(
                agentMetrics,
                "AgentMetrics不能为空"
        );

        this.messageId = UUID.randomUUID()
                .toString()
                .replace("-", "");

        ResponseStreamContext responseContext =
                new ResponseStreamContext(
                        messageId,
                        this.runId,
                        this.conversationId
                );

        // 将会话中的同一个序号生成器交给回答事件工厂。
        this.responseEventFactory = new ResponseStreamEventFactory(
                responseContext,
                checksumService,
                sequenceGenerator
        );

        this.chatResponseAccumulator =
                new ChatResponseAccumulator(responseContext);

        this.agentMetrics.recordSseOpened();
    }

    /**
     * 发送进度事件。连接断开只停止推送，不中断业务处理。
     */
    public synchronized void send(String eventName, AgentStreamEvent event) throws Exception {
        if (completed.get() || !connectionOpen.get()) {
            return;
        }
        Objects.requireNonNull(event, "进度事件不能为空");
        long currentSequence = sequenceGenerator.next();
        String eventId = runId + "-" + currentSequence;

        event.setRunId(runId);
        event.setMessageId(messageId);
        event.setEventId(eventId);
        event.setSequence(currentSequence);
        event.setTimestamp(System.currentTimeMillis());

        try {
            emitter.send(SseEmitter.event()
                    .id(eventId)
                    .name(eventName)
                    .data(event));
        } catch (Exception exception) {
            if (!connectionOpen.get() || isClientDisconnected(exception)) {
                closeConnection("DISCONNECTED");
                return;
            }

            throw exception;
        }

        agentMetrics.recordSseEvent(event.getType());
    }

    /**
     * 绑定执行线程。排队期间已经取消的任务，只进入取消收尾。
     */
    public synchronized void bindExecutionThread() {
        executionThread = Thread.currentThread();
        checkCancellation();
    }

    /**
     * 清理线程引用和中断标志，避免影响线程池后续任务。
     */
    public synchronized void unbindExecutionThread() {
        executionThread = null;
        Thread.interrupted();
    }

    /**
     * 接受用户取消请求。
     * 最终保存已经开始时返回 false，不能同时承诺完成和取消。
     */
    public synchronized boolean requestCancellation() {
        if (completed.get() || finalizing || cancellationRequested) {
            return false;
        }

        cancellationRequested = true;

        if (executionThread != null) {
            executionThread.interrupt();
        }

        return true;
    }

    /**
     * 在处理边界检查取消意图。
     */
    public synchronized void checkCancellation() {
        if (cancellationRequested || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("回答已由用户终止");
        }
    }

    /**
     * 只读业务查询停止信号，不发送事件，也不改变其他回答的断线收尾状态。
     */
    public boolean shouldStopBusinessQuery() {
        return cancellationRequested || !connectionOpen.get() || Thread.currentThread().isInterrupted();
    }

    /**
     * 正常或失败结果进入最终保存。
     * 必须在数据库保存之前调用，不能放到发送完成事件之后。
     */
    public synchronized void beginFinalization() {
        checkCancellation();
        finalizing = true;
    }

    /**
     * 异常收尾期间禁止再次中断保存，并返回此前是否接受过用户取消。
     */
    public synchronized boolean beginInterruptedFinalization() {
        finalizing = true;
        return cancellationRequested;
    }

    /**
     * 只关闭网络连接，后台任务仍可继续累计内容和保存结果。
     */
    private void closeConnection(String reason) {
        if (!connectionOpen.compareAndSet(true, false)) {
            return;
        }

        try {
            emitter.complete();
        } catch (RuntimeException exception) {
            log.debug("关闭SSE连接失败，runId={}，errorType={}",
                    runId, exception.getClass().getSimpleName());
        } finally {
            agentMetrics.recordSseClosed(reason);
        }
    }

    /**
     * 发送统一回答事件，继续使用原来的共享事件序号。
     */
    public synchronized void sendResponseEvent(ResponseStreamEvent<?> event) throws Exception {

        if (completed.get() || !connectionOpen.get()) {
            return;
        }

        Objects.requireNonNull(event, "发送的响应事件不能为空");

        String eventName = event.eventType().name().toLowerCase(Locale.ROOT);

        try {
            emitter.send(SseEmitter.event()
                    .id(event.eventId())
                    .name(eventName)
                    .data(event));
        } catch (Exception exception) {
            if (!connectionOpen.get() || isClientDisconnected(exception)) {
                closeConnection("DISCONNECTED");
                return;
            }

            throw exception;
        }

        agentMetrics.recordSseEvent(event.eventType().name());

        boolean firstVisibleContent =
                event.eventType() == ResponseStreamEventType.BLOCK_DELTA
                        || event.eventType() == ResponseStreamEventType.BLOCK_DONE;

        if (firstVisibleContent
                && firstContentRecorded.compareAndSet(false, true)) {
            agentMetrics.recordFirstContentDuration(
                    System.currentTimeMillis() - startedAt
            );
        }
    }

    /**
     * 开始CHAT模式回答。
     */
    public synchronized void startChatResponse()
            throws Exception {

        startResponse(
                PresentationMode.CHAT
        );
    }

    /**
     * 开始REPORT模式回答。
     */
    public synchronized void startReportResponse()
            throws Exception {

        startResponse(
                PresentationMode.REPORT
        );
    }

    /**
     * 开始统一响应。
     */
    private void startResponse(
            PresentationMode mode) throws Exception {

        if (completed.get()) {
            throw new IllegalStateException(
                    "SSE会话已经结束"
            );
        }

        if (responseStarted) {
            if (responseMode != mode) {
                throw new IllegalStateException(
                        "同一次SSE响应不能同时使用"
                                + responseMode
                                + "和"
                                + mode
                                + "模式"
                );
            }

            return;
        }

        sendResponseEvent(
                responseEventFactory.responseStart(
                        mode,
                        true
                )
        );

        responseMode = mode;
        responseStarted = true;
    }

    /**
     * 开始一个流式TEXT区块。
     */
    public synchronized void startTextResponse(
            String blockId,
            String title,
            int order,
            BlockSource source) throws Exception {

        startChatResponse();

        if (activeTextBlock != null) {
            if (activeTextBlock.blockId().equals(blockId)) {
                return;
            }

            throw new IllegalStateException(
                    "已有TEXT区块正在生成，blockId="
                            + activeTextBlock.blockId()
            );
        }

        BlockStartPayload payload = new BlockStartPayload(
                blockId,
                BlockType.TEXT,
                title,
                order,
                source
        );

        chatResponseAccumulator.startText(payload);
        activeTextBlock = payload;
        activeTextDeltaIndex = 0;

        sendResponseEvent(
                responseEventFactory.blockStart(payload)
        );
    }

    /**
     * 追加当前TEXT区块的增量内容。
     */
    public synchronized void appendTextResponse(
            String delta) throws Exception {

        if (activeTextBlock == null) {
            throw new IllegalStateException(
                    "当前没有正在生成的TEXT区块"
            );
        }

        if (delta == null || delta.isEmpty()) {
            return;
        }

        BlockDeltaPayload payload = new BlockDeltaPayload(
                activeTextBlock.blockId(),
                ++activeTextDeltaIndex,
                delta
        );

        chatResponseAccumulator.appendText(payload);

        sendResponseEvent(
                responseEventFactory.blockDelta(payload)
        );
    }

    /**
     * 完成当前TEXT区块。
     *
     * BLOCK_DONE携带最终完整内容，
     * 前端按blockId替换增量内容。
     */
    public synchronized void finishTextResponse(
            String finalMarkdown) throws Exception {

        if (activeTextBlock == null) {
            throw new IllegalStateException(
                    "当前没有正在生成的TEXT区块"
            );
        }

        TextBlock textBlock = new TextBlock(
                activeTextBlock.blockId(),
                activeTextBlock.title(),
                activeTextBlock.order(),
                BlockStatus.READY,
                activeTextBlock.source(),
                finalMarkdown == null ? "" : finalMarkdown
        );

        chatResponseAccumulator.completeBlock(textBlock);

        sendResponseEvent(
                responseEventFactory.blockDone(textBlock)
        );

        activeTextBlock = null;
        activeTextDeltaIndex = 0;
    }

    /**
     * 发布一个完整结构化区块。
     *
     * 指标、键值、表格、状态和风险区块，
     * 都通过该方法一次发送完整对象。
     */
    public synchronized void publishResponseBlock(
            ResponseBlock block) throws Exception {

        startChatResponse();
        chatResponseAccumulator.completeBlock(block);

        sendResponseEvent(
                responseEventFactory.blockDone(block)
        );
    }

    /**
     * 标记一个区块生成失败。
     *
     * 不清除已经成功发送的其他区块。
     */
    public synchronized void failResponseBlock(
            BlockErrorPayload payload) throws Exception {

        startChatResponse();
        chatResponseAccumulator.failBlock(payload);

        sendResponseEvent(
                responseEventFactory.blockError(payload)
        );

        if (activeTextBlock != null
                && activeTextBlock.blockId()
                .equals(payload.blockId())) {

            activeTextBlock = null;
            activeTextDeltaIndex = 0;
        }
    }

    /**
     * 更新业务基础数据完整状态。
     */
    public synchronized void setResponseDataComplete(
            boolean dataComplete) {

        chatResponseAccumulator.setDataComplete(
                dataComplete
        );
    }

    /**
     * 更新知识库引用。
     */
    public synchronized void setResponseReferences(List<ResponseReference> references) {
        chatResponseAccumulator.setReferences(references);
    }

    /**
     * 更新回答运行信息。
     */
    public synchronized void setResponseMeta(ResponseMeta meta) {

        chatResponseAccumulator.setMeta(meta);
    }

    /**
     * 发送当前完整回答快照。
     */
    public synchronized void sendResponseSnapshot() throws Exception {
        startChatResponse();
        sendResponseEvent(responseEventFactory.responseSnapshot(chatResponseAccumulator.snapshot()));
    }

    /**
     * 保存并发送当前报告快照，不等待AI分析完成。
     */
    public synchronized void sendReportSnapshot(ReportSchema report) throws Exception {
        currentReport = Objects.requireNonNull(report, "报告快照不能为空");
        startReportResponse();
        sendResponseEvent(responseEventFactory.responseSnapshot(report));
    }

    /**
     * 发送已经整理好的异常或取消结果。
     * 持久化失败时明确提示，不能伪装为保存成功。
     */
    public synchronized void finishInterruptedResponse(ResponseDocument document, Throwable failure, boolean persisted) {
        if (completed.get()) {
            return;
        }

        Objects.requireNonNull(document, "最终响应不能为空");

        boolean cancelled = document.status() == ResponseStatus.CANCELLED;

        try {
            startResponse(document.mode());

            if (cancelled && persisted) {
                sendResponseEvent(responseEventFactory.responseDone(document));
            } else {
                String errorCode = persisted
                        ? "AGENT_PROCESSING_FAILED"
                        : "RESPONSE_PERSISTENCE_FAILED";

                String errorMessage = persisted
                        ? safeErrorMessage(failure)
                        : "本次回答已结束，但最终结果保存失败。"
                          + "当前内容仍可查看，刷新后可能无法恢复。";

                sendResponseEvent(responseEventFactory.responseError(
                        errorCode,
                        errorMessage,
                        true,
                        document
                ));
            }
        } catch (Exception sendException) {
            if (!isClientDisconnected(sendException)) {
                log.warn("发送最终响应失败，runId={}，errorType={}",
                        runId, sendException.getClass().getSimpleName());
            }
        } finally {
                completed.set(true);
                closeConnection(cancelled ? "CANCELLED" : "ERROR");
       }
    }
    /**
     * 完成报告响应，调用方必须已经保存最终快照。
     */
    public synchronized void finishReportResponse(ReportSchema report) throws Exception {
        finishResponse(report);
    }


    /**
     * 完成报告取消响应，调用方必须已经保存取消快照。
     */
    public synchronized void cancelReportResponse(ReportSchema report) {
        finishResponse(report);
    }

    /**
     * 完成文字回答，调用方必须已经保存最终快照。
     */
    public synchronized void finishChatResponse() throws Exception {
        if (completed.get()) {
            return;
        }
        if (activeTextBlock != null) {
            throw new IllegalStateException(
                    "TEXT区块尚未完成，blockId=" + activeTextBlock.blockId()
            );
        }

        finishResponse(chatResponseAccumulator.complete());
    }


    /**
     * 整理取消快照，不提前发送完成事件。
     */
    public synchronized AiResponse prepareCancelledChatResponse(String finalMarkdown) {
        beginInterruptedFinalization();
        Thread.interrupted();
        if (responseMode == PresentationMode.REPORT) {
            throw new IllegalStateException(
                    "REPORT响应必须通过报告取消流程收尾"
            );
        }

        if (activeTextBlock != null && finalMarkdown != null) {
            TextBlock cancelledText = new TextBlock(
                    activeTextBlock.blockId(),
                    activeTextBlock.title(),
                    activeTextBlock.order(),
                    BlockStatus.CANCELLED,
                    activeTextBlock.source(),
                    finalMarkdown
            );

            chatResponseAccumulator.completeBlock(cancelledText);
        }

        activeTextBlock = null;
        activeTextDeltaIndex = 0;

        return chatResponseAccumulator.cancel();
    }

    /**
     * 完成文字回答取消，调用方必须已经保存取消快照。
     */
    public synchronized void cancelChatResponse() {
        if (completed.get()) {
            return;
        }
        finishResponse(chatResponseAccumulator.cancel());
    }

    /**
     * 最终结果已经保存，发送失败不能再把它改写成另一份失败快照。
     * 本方法由持有会话锁的公开方法调用。
     */
    private void finishResponse(ResponseDocument document) {
        if (completed.get()) {
            return;
        }
        Objects.requireNonNull(document, "最终响应不能为空");
        if (document instanceof ReportSchema report) {
            currentReport = report;
        }
        try {
            startResponse(document.mode());
            sendResponseEvent(responseEventFactory.responseDone(document));
        } catch (Exception exception) {
            log.warn("最终结果已保存，但完成事件未能发送，runId={}，errorType={}",
                    runId, exception.getClass().getSimpleName());
        } finally {
            completed.set(true);
            closeConnection(document.status() == ResponseStatus.CANCELLED ? "CANCELLED" : "SUCCESS");
        }
    }

    /**
     * 结束只包含运行事件或写操作提示的响应。
     */
    public synchronized void complete() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }

        closeConnection("SUCCESS");
    }

    /**
     * 无法构建正常回答快照时，发送统一错误并结束业务。
     */
    public synchronized void error(Throwable throwable) {
        if (completed.get()) {
            return;
        }

        if (isClientDisconnected(throwable)) {
            closeConnection("DISCONNECTED");
            return;
        }
        try {
            sendResponseEvent(responseEventFactory.responseError(
                    "AGENT_PROCESSING_FAILED",
                    safeErrorMessage(throwable),
                    true,
                    null
            ));
        } catch (Exception exception) {
            log.warn("发送统一错误事件失败，runId={}，errorType={}",
                    runId, exception.getClass().getSimpleName());
        } finally {
            completed.set(true);
            closeConnection("ERROR");
        }
    }

    /**
     * SSE连接超时不等于模型执行超时，不取消后台回答。
     */
    public void timeout() {
        closeConnection("TIMEOUT");
    }

    /**
     * 浏览器或容器关闭连接，只更新连接状态。
     */
    public void connectionClosed() {
        closeConnection("DISCONNECTED");
    }

    /**
     * 判断业务回答是否已经结束，不用于判断网络连接。
     */
    public boolean isCompleted() {
        return completed.get();
    }



    /**
     * 获取安全错误信息。
     */
    private String safeErrorMessage( Throwable throwable) {
        if (throwable instanceof BusinessException && StringUtils.hasText(throwable.getMessage())) {
            return throwable.getMessage();
        }
        /*
         * 详细异常只写服务端日志，
         * 前端不能看到SQL、URL或内部组件信息。
         */
        log.error(
                "Agent SSE处理失败，runId={}",
                runId,
                throwable
        );
        return "Agent处理失败，请稍后重试。";
    }

    /**
     * 识别连接断开，不把普通业务异常当成网络断开。
     */
    private boolean isClientDisconnected(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof AgentClientDisconnectedException
                    || current instanceof java.net.SocketException
                    || current instanceof java.io.EOFException) {
                return true;
            }

            String className = current.getClass().getName();
            if ("org.springframework.web.context.request.async.AsyncRequestNotUsableException"
                    .equals(className)
                    || "org.apache.catalina.connector.ClientAbortException"
                    .equals(className)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }

        return false;
    }


    /**
     * 校验必要的会话标识。
     */
    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
