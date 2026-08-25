package org.example.ai.agent.chat.support;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentStreamEvent;
import org.example.ai.agent.common.config.AgentStreamProperties;
import org.example.ai.agent.common.enums.AgentStreamEventType;
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

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

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

    private final AtomicLong sequence = new AtomicLong(0);
    /**
     * SSE会话创建时间。
     */
    private final long startedAt = System.currentTimeMillis();
    private final AtomicBoolean completed = new AtomicBoolean(false);

    /**
     * 创建统一协议SSE会话。
     */
    public AgentStreamSession(SseEmitter emitter, String runId, String conversationId,
                              AgentMetrics agentMetrics, ResponseChecksumService checksumService) {

        this.emitter = Objects.requireNonNull(
                        emitter,
                        "SseEmitter不能为空"
                );

        this.runId =
                requireText(
                        runId,
                        "runId不能为空"
                );

        this.conversationId =
                requireText(
                        conversationId,
                        "conversationId不能为空"
                );

        this.agentMetrics =
                Objects.requireNonNull(
                        agentMetrics,
                        "AgentMetrics不能为空"
                );

        this.messageId =
                UUID.randomUUID()
                        .toString()
                        .replace("-", "");

        ResponseStreamContext responseContext =
                new ResponseStreamContext(
                        messageId,
                        runId,
                        conversationId
                );

        this.responseEventFactory =
                new ResponseStreamEventFactory(
                        responseContext,
                        checksumService
                );

        this.chatResponseAccumulator =
                new ChatResponseAccumulator(
                        responseContext
                );

        this.agentMetrics.recordSseOpened();
    }

    /**
     * 发送运行进度类事件。
     *
     * THINKING、PLAN、FACTS等运行过程信息仍使用该方法；
     * 最终CHAT和REPORT回答使用统一响应事件。
     */
    public synchronized void send(
            String eventName,
            AgentStreamEvent event) throws Exception {

        if (completed.get()) {
            return;
        }

        long currentSequence =
                sequence.incrementAndGet();

        String eventId =
                runId + "-" + currentSequence;

        event.setRunId(runId);
        event.setMessageId(messageId);
        event.setEventId(eventId);
        event.setSequence(currentSequence);
        event.setTimestamp(
                System.currentTimeMillis()
        );

        try {
            emitter.send(
                    SseEmitter.event()
                            .id(eventId)
                            .name(eventName)
                            .data(event)
            );
        } catch (Exception exception) {
            if (isClientDisconnected(exception)) {
                completed.set(true);

                log.debug(
                        "SSE客户端已断开，停止发送事件，"
                                + "runId={}，eventType={}",
                        runId,
                        event.getType()
                );

                throw new AgentClientDisconnectedException(
                        "SSE客户端连接已断开",
                        exception
                );
            }

            throw exception;
        }

        agentMetrics.recordSseEvent(event.getType());

        /*
         * FACTS属于用户能够看到的首批业务内容。
         * CHAT回答的首内容由BLOCK事件单独统计。
         */
        boolean firstVisibleContent =
                AgentStreamEventType.FACTS
                        .name()
                        .equalsIgnoreCase(
                                event.getType()
                        );

        if (firstVisibleContent
                && firstContentRecorded
                .compareAndSet(false, true)) {
            agentMetrics.recordFirstContentDuration(System.currentTimeMillis() - startedAt);
        }
    }

    /**
     * 发送新版统一响应事件。
     *
     * 新版事件已经包含eventId和sequence，
     * 这里不再重复生成事件编号。
     */
    public synchronized void sendResponseEvent(ResponseStreamEvent<?> event) throws Exception {
        if (completed.get()) {
            return;
        }
        Objects.requireNonNull(event, "发送的响应事件不能为空");

        String eventName = event.eventType().name().toLowerCase(Locale.ROOT);
        try {
            emitter.send(SseEmitter.event().id(event.eventId()).name(eventName).data(event));
        } catch (Exception exception) {
            if (isClientDisconnected(exception)) {
                completed.set(true);
                log.debug(
                        "SSE客户端已断开，停止发送新版事件，runId={}，eventType={}",
                        runId,
                        event.eventType()
                );
                throw new AgentClientDisconnectedException(
                        "SSE客户端连接已断开",
                        exception
                );
            }
            throw exception;
        }
        agentMetrics.recordSseEvent(event.eventType().name());
        boolean firstVisibleContent = event.eventType() == ResponseStreamEventType.BLOCK_DELTA
                        || event.eventType() == ResponseStreamEventType.BLOCK_DONE;

        if (firstVisibleContent && firstContentRecorded.compareAndSet(false, true)) {
            agentMetrics.recordFirstContentDuration(System.currentTimeMillis() - startedAt);
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
     * 发送当前完整基础报告快照。
     *
     * 基础业务数据准备完成后立即发送，
     * 不等待AI分析结束。
     */
    public synchronized void sendReportSnapshot(
            ReportSchema report) throws Exception {

        startReportResponse();

        sendResponseEvent(
                responseEventFactory.responseSnapshot(
                        report
                )
        );
    }

    /**
     * 正常完成REPORT响应。
     */
    public synchronized void finishReportResponse(
            ReportSchema report) throws Exception {

        if (completed.get()) {
            return;
        }

        startReportResponse();

        sendResponseEvent(
                responseEventFactory.responseDone(
                        report
                )
        );

        completed.set(true);
        emitter.complete();

        agentMetrics.recordSseClosed("SUCCESS");
    }

    /**
     * 报告处理失败。
     *
     * snapshot保留已经成功生成的基础报告，
     * 避免AI分析异常导致业务数据全部消失。
     */
    public synchronized void failReportResponse(
            Throwable throwable,
            ReportSchema snapshot) {

        if (completed.get()) {
            return;
        }

        try {
            startReportResponse();

            sendResponseEvent(
                    responseEventFactory.responseError(
                            "REPORT_PROCESSING_FAILED",
                            safeErrorMessage(throwable),
                            true,
                            snapshot
                    )
            );
        } catch (Exception sendException) {
            if (!isClientDisconnected(sendException)) {
                log.error(
                        "发送报告错误事件失败，runId={}",
                        runId,
                        sendException
                );
            }
        } finally {
            completed.set(true);
            emitter.complete();
            agentMetrics.recordSseClosed("ERROR");
        }
    }

    /**
     * 用户主动终止报告分析。
     *
     * 基础报告仍然保留，
     * 最终状态通过ReportSchema中的CANCELLED表达。
     */
    public synchronized void cancelReportResponse(
            ReportSchema report) {

        if (completed.get()) {
            return;
        }

        try {
            startReportResponse();

            sendResponseEvent(
                    responseEventFactory.responseDone(
                            report
                    )
            );
        } catch (Exception sendException) {
            if (!isClientDisconnected(sendException)) {
                log.warn(
                        "发送报告取消事件失败，runId={}",
                        runId
                );
            }
        } finally {
            completed.set(true);
            emitter.complete();
            agentMetrics.recordSseClosed("CANCELLED");
        }
    }

    /**
     * 正常完成CHAT回答。
     */
    public synchronized void finishChatResponse() throws Exception {
        if (completed.get()) {
            return;
        }
        if (activeTextBlock != null) {
            throw new IllegalStateException("TEXT区块尚未完成，blockId=" + activeTextBlock.blockId());
        }
        AiResponse finalResponse = chatResponseAccumulator.complete();
        sendResponseEvent(responseEventFactory.responseDone(finalResponse));
        completed.set(true);
        emitter.complete();
        agentMetrics.recordSseClosed("SUCCESS");
    }

    /**
     * CHAT回答整体失败。
     *
     * 已经生成的区块通过snapshot保留。
     */
    public synchronized void failChatResponse(Throwable throwable) {

        if (completed.get()) {
            return;
        }
        AiResponse snapshot = chatResponseAccumulator.fail();

        try {
            sendResponseEvent(responseEventFactory.responseError("AGENT_PROCESSING_FAILED", safeErrorMessage(throwable), true, snapshot));
        } catch (Exception sendException) {
            if (!isClientDisconnected(sendException)) {
                log.error("发送新版回答错误事件失败，runId={}", runId, sendException);
            }
        } finally {
            completed.set(true);
            emitter.complete();
            agentMetrics.recordSseClosed("ERROR");
        }
    }

    /**
     * 用户主动终止CHAT回答。
     */
    public synchronized void cancelChatResponse() {
        if (completed.get()) {
            return;
        }

        AiResponse cancelledResponse = chatResponseAccumulator.cancel();

        try {
            sendResponseEvent(responseEventFactory.responseDone(cancelledResponse));
        } catch (Exception sendException) {
            if (!isClientDisconnected(sendException)) {
                log.warn("发送回答取消事件失败，runId={}", runId);
            }
        } finally {
            completed.set(true);
            emitter.complete();
            agentMetrics.recordSseClosed("CANCELLED");
        }
    }



    /**
     * 关闭只包含运行事件或写操作事件的SSE连接。
     *
     * CHAT和REPORT回答分别使用
     * finishChatResponse和finishReportResponse结束。
     */
    public synchronized void complete() {

        if (!completed.compareAndSet(false, true)) {
            return;
        }

        emitter.complete();

        agentMetrics.recordSseClosed(
                "SUCCESS"
        );
    }

    /**
     * 使用统一错误事件结束当前请求。
     */
    public synchronized void error(
            Throwable throwable) {

        if (!completed.compareAndSet(false, true)) {
            return;
        }

        if (isClientDisconnected(throwable)) {
            log.debug(
                    "SSE客户端已断开，跳过错误事件发送，runId={}",
                    runId
            );

            agentMetrics.recordSseClosed(
                    "CANCELLED"
            );

            return;
        }

        try {
            ResponseStreamEvent<?> event =
                    responseEventFactory.responseError(
                            "AGENT_PROCESSING_FAILED",
                            safeErrorMessage(throwable),
                            true,
                            null
                    );

            emitter.send(
                    SseEmitter.event()
                            .id(event.eventId())
                            .name(
                                    event.eventType()
                                            .name()
                                            .toLowerCase(Locale.ROOT)
                            )
                            .data(event)
            );

            agentMetrics.recordSseEvent(
                    event.eventType().name()
            );
        } catch (Exception sendException) {
            if (!isClientDisconnected(sendException)) {
                log.warn(
                        "发送统一错误事件失败，"
                                + "runId={}，errorType={}",
                        runId,
                        sendException
                                .getClass()
                                .getSimpleName()
                );
            }
        } finally {
            emitter.complete();

            agentMetrics.recordSseClosed(
                    "ERROR"
            );
        }
    }

    /**
     * 客户端连接超时。
     */
    public void timeout() {
        error(new IllegalStateException( "Agent回答超时"));
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
     * 判断异常是否源于客户端断开连接。
     *
     * 不直接 import 容器实现类，通过类名判断，
     * 兼容 Spring 异步请求不可用和 Tomcat 客户端中止两类异常，
     * 并递归检查 cause 链。
     */
    private boolean isClientDisconnected(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        if (throwable instanceof AgentClientDisconnectedException) {
            return true;
        }
        String className = throwable.getClass().getName();
        if ("org.springframework.web.context.request.async.AsyncRequestNotUsableException"
                .equals(className)) {
            return true;
        }
        if ("org.apache.catalina.connector.ClientAbortException".equals(className)) {
            return true;
        }
        return isClientDisconnected(throwable.getCause());
    }

    /**
     * SSE会话是否已经结束。
     */
    public boolean isCompleted() {
        return completed.get();
    }

    /**
     * 客户端或容器提前关闭连接。
     */
    public void connectionClosed() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        agentMetrics.recordSseClosed("CANCELLED");
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