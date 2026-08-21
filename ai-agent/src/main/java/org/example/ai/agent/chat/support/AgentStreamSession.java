package org.example.ai.agent.chat.support;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.chat.entity.AgentStreamEvent;
import org.example.ai.agent.chat.vo.AnswerCompleteData;
import org.example.ai.agent.common.config.AgentStreamProperties;
import org.example.ai.agent.common.enums.AgentStreamEventType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.observability.AgentMetrics;
import org.flywaydb.core.internal.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
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
     * -- GETTER --
     *  Controller 最终需要返回底层 SseEmitter。
     */
    @Getter
    private final SseEmitter emitter;
    private final AgentStreamProperties properties;
    private final MarkdownChunker markdownChunker;
    private final AgentMetrics agentMetrics;
    /**
     * 是否已经记录首个有效内容。
     */
    private final AtomicBoolean firstContentRecorded =  new AtomicBoolean(false);
    @Getter
    private final String runId;

    @Getter
    private final String messageId;

    @Getter
    private final int protocolVersion;

    private final AtomicLong sequence = new AtomicLong(0);
    /**
     * SSE会话创建时间。
     */
    private final long startedAt = System.currentTimeMillis();
    private final AtomicBoolean completed = new AtomicBoolean(false);
    /**
     * 是否已经启动增量文字回答。
     */
    private boolean incrementalAnswerStarted;
    /**
     * 当前助手消息是否已经写入数据库。
     */
    private final AtomicBoolean assistantMessagePersisted =new AtomicBoolean(false);
    /**
     * 累计真实流式回答内容。
     */
    private final StringBuilder incrementalAnswer =new StringBuilder();
    /**
     * 最终完整 Markdown。
     */
    private volatile String finalMarkdown = "";
    /**
     *  当前回答最终展示类型。
     */
    private volatile String finalPresentationType ="MARKDOWN";

    /**
     *  当前回答报告标题。
     */
    private volatile String finalPresentationTitle;
    /**
     * 用户请求的首选模型。
     */
    private volatile String requestedModelCode;

    /**
     * 最终成功完成回答的模型。
     */
    private volatile String effectiveModelCode;

    /**
     * 是否发生了自动模型切换。
     */
    private volatile boolean fallbackUsed;

    public AgentStreamSession(
            SseEmitter emitter,
            String runId,
            Integer requestedVersion,
            AgentStreamProperties properties,
            MarkdownChunker markdownChunker ,AgentMetrics agentMetrics) {
        this.emitter = emitter;
        this.runId = runId;
        this.properties = properties;
        this.markdownChunker = markdownChunker;
        this.messageId = UUID.randomUUID().toString() .replace("-", "");
        this.protocolVersion =normalizeVersion( requestedVersion, properties.getDefaultVersion());
        this.agentMetrics = agentMetrics;
        this.agentMetrics.recordSseOpened(protocolVersion);
    }

    /**
     * 发送普通事件。
     *
     * synchronized 保证同一连接中事件顺序稳定。
     */
    public synchronized void send(String eventName,AgentStreamEvent event) throws Exception {
        if (completed.get()) {
            return;
        }
        /*
         * 报告事件必须同步更新最终展示协议，
         * 避免 complete() 发送 ANSWER_DONE 时回退为 MARKDOWN。
         */
        if ("REPORT".equalsIgnoreCase(event.getPresentationType())) {
            finalPresentationType = "REPORT";
            String presentationTitle = event.getPresentationTitle();
            if (presentationTitle != null && !presentationTitle.isBlank()) {
                finalPresentationTitle = presentationTitle.substring(0,Math.min(presentationTitle.length(), 128));
            }
        }
        long currentSequence = sequence.incrementAndGet();

        String eventId = runId + "-" + currentSequence;

        event.setRunId(runId);
        event.setMessageId(messageId);
        event.setEventId(eventId);
        event.setSequence(currentSequence);
        event.setTimestamp(System.currentTimeMillis());
        try {
            emitter.send(SseEmitter.event().id(eventId).name(eventName) .data(event));
        } catch (Exception exception) {
            /*
             * 客户端断开（刷新/关闭/取消/网络中断）时，写入必然失败。
             * 标记会话已断开并抛出专用异常，
             * 让调用方静默收尾，不再当作业务异常处理。
             */
            if (isClientDisconnected(exception)) {
                completed.set(true);
                log.debug("SSE客户端已断开，停止发送事件，runId={}，eventType={}",
                        runId, event.getType());
                throw new AgentClientDisconnectedException(
                        "SSE客户端连接已断开",
                        exception
                );
            }
            throw exception;
        }
        agentMetrics.recordSseEvent( protocolVersion,event.getType());
        boolean firstVisibleContent =AgentStreamEventType.FACTS.name()
                        .equalsIgnoreCase(event.getType())
                        || AgentStreamEventType.ANSWER_DELTA.name()
                        .equalsIgnoreCase(event.getType())
                        || "ANSWER".equalsIgnoreCase(event.getType());
        /*
         * FACTS或ANSWER_DELTA是用户首次看到有效结果的时间点。
         */
        if (firstVisibleContent && firstContentRecorded.compareAndSet(false, true)) {
            agentMetrics.recordFirstContentDuration(
                    protocolVersion,
                    System.currentTimeMillis() - startedAt
            );
        }
    }

    /**
     * 发布最终回答。
     *
     * presentationType由后端明确决定，
     * 前端不再检查Markdown内容猜测报告类型。
     */
    public void publishAnswer(String markdown, String presentationType, String presentationTitle) throws Exception {
        finalMarkdown = markdown == null ? "": markdown;
        /*
         *  
         * 当前协议只开放REPORT和MARKDOWN。
         * 其他值统一降级为普通Markdown。
         */
        finalPresentationType =
                "REPORT".equalsIgnoreCase(presentationType)
                        ? "REPORT"
                        : "MARKDOWN";

        String normalizedTitle =presentationTitle == null ? null
                        : presentationTitle.trim();

        /*
         *  
         * 报告标题不允许无限增长。
         */
        finalPresentationTitle =normalizedTitle == null
                        || normalizedTitle.isBlank()
                        ? null
                        : normalizedTitle.substring(
                        0,
                        Math.min(normalizedTitle.length(),128 ));

        agentMetrics.recordAnswerLength(
                protocolVersion,
                finalMarkdown.length()
        );

        /*
         * v1协议使用单个ANSWER事件。
         */
        if (protocolVersion == 1) {
            send("answer",AgentStreamEvent.builder()
                            .runId(runId)
                            .type("ANSWER")
                            .content(finalMarkdown)
                            .presentationType(
                                    finalPresentationType
                            )
                            .presentationTitle(
                                    finalPresentationTitle
                            )
                            .build()
            );
            return;
        }

        /*
         * v2开始事件携带展示协议。
         */
        send("answer_start",
                AgentStreamEvent.builder()
                        .runId(runId)
                        .type(AgentStreamEventType.ANSWER_START.name())
                        .content("")
                        .presentationType(
                                finalPresentationType
                        )
                        .presentationTitle(
                                finalPresentationTitle
                        )
                        .build()
        );

        List<String> chunks =
                markdownChunker.split(
                        finalMarkdown,
                        properties.getChunkSize()
                );

        for (String chunk : chunks) {
            send("answer_delta",
                    AgentStreamEvent.of( runId,
                            AgentStreamEventType.ANSWER_DELTA.name(),
                            chunk,
                            null));
        }

        /*
         * 最终快照再次携带展示协议，
         * 防止ANSWER_START事件丢失。
         */
        if (properties.isSnapshotEnabled()) {
            send(
                    "answer_snapshot",
                    AgentStreamEvent.builder()
                            .runId(runId)
                            .type(
                                    AgentStreamEventType
                                            .ANSWER_SNAPSHOT
                                            .name()
                            )
                            .content(finalMarkdown)
                            .contentLength(
                                    finalMarkdown.length()
                            )
                            .contentHash(
                                    ContentHashUtils.sha256(
                                            finalMarkdown
                                    )
                            )
                            .presentationType(
                                    finalPresentationType
                            )
                            .presentationTitle(
                                    finalPresentationTitle
                            )
                            .build()
            );
        }
    }

    /**
     * 开始真正的增量文字回答。
     *
     * SSE v2只发送一次ANSWER_START；
     * SSE v1继续在finishAnswer时发送完整ANSWER。
     */
    public synchronized void startAnswer(
            String presentationType) throws Exception {

        if (completed.get()) {
            throw new IllegalStateException(
                    "SSE会话已经结束"
            );
        }

        if (incrementalAnswerStarted) {
            return;
        }

        incrementalAnswerStarted = true;
        incrementalAnswer.setLength(0);
        finalMarkdown = "";

        finalPresentationType =
                "REPORT".equalsIgnoreCase(presentationType)
                        ? "REPORT"
                        : "MARKDOWN";

        finalPresentationTitle = null;

        if (protocolVersion == 1) {
            return;
        }

        send(
                "answer_start",
                AgentStreamEvent.builder()
                        .runId(runId)
                        .type(
                                AgentStreamEventType
                                        .ANSWER_START
                                        .name()
                        )
                        .content("")
                        .presentationType(
                                finalPresentationType
                        )
                        .build()
        );
    }

    /**
     * 追加当前真实到达的模型片段。
     */
    public synchronized void appendAnswerDelta(
            String content) throws Exception {

        if (completed.get()) {
            throw new IllegalStateException(
                    "SSE会话已经结束"
            );
        }

        if (!incrementalAnswerStarted) {
            throw new IllegalStateException(
                    "增量回答尚未开始"
            );
        }

        if (content == null || content.isEmpty()) {
            return;
        }

        incrementalAnswer.append(content);
        finalMarkdown = incrementalAnswer.toString();

        if (protocolVersion == 1) {
            return;
        }

        send(
                "answer_delta",
                AgentStreamEvent.of(
                        runId,
                        AgentStreamEventType
                                .ANSWER_DELTA
                                .name(),
                        content,
                        null
                )
        );
    }

    /**
     * 完成增量文字回答。
     *
     * 该方法只发送最终快照，
     * ANSWER_DONE和emitter关闭仍由complete()统一处理，
     * 避免重复发送结束事件。
     */
    public synchronized void finishAnswer(String finalContent) throws Exception {
        if (completed.get()) {
            return;
        }
        if (!incrementalAnswerStarted) {
            throw new IllegalStateException(
                    "增量回答尚未开始"
            );
        }
        finalMarkdown = finalContent == null
                        ? incrementalAnswer.toString()
                        : finalContent;
        agentMetrics.recordAnswerLength(protocolVersion, finalMarkdown.length());

        /*
         * SSE v1保持原来的单个ANSWER事件。
         */
        if (protocolVersion == 1) {
            publishAnswer(finalMarkdown, finalPresentationType, finalPresentationTitle);
            complete();
            return;
        }

        if (properties.isSnapshotEnabled()) {
            send("answer_snapshot", AgentStreamEvent.builder()
                            .runId(runId)
                            .type(
                                    AgentStreamEventType
                                            .ANSWER_SNAPSHOT
                                            .name()
                            )
                            .content(finalMarkdown)
                            .contentLength(
                                    finalMarkdown.length()
                            )
                            .contentHash(
                                    ContentHashUtils.sha256(
                                            finalMarkdown
                                    )
                            )
                            .presentationType(
                                    finalPresentationType
                            )
                            .presentationTitle(
                                    finalPresentationTitle
                            )
                            .build()
            );
        }
        /*
         * complete()是唯一发送ANSWER_DONE的位置。
         */
        complete();
    }

    /**
     * 正常完成回答并关闭连接。
     */
    public synchronized void complete()throws Exception {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        String closeReason = "SUCCESS";
        try {
            String contentHash = ContentHashUtils.sha256(finalMarkdown);

            long currentSequence =sequence.incrementAndGet();

            String eventId =runId + "-" + currentSequence;

            String eventName = protocolVersion == 1 ? "done"
                    : "answer_done";
            String eventType = protocolVersion == 1  ? "DONE"
                    : AgentStreamEventType.ANSWER_DONE.name();

            AnswerCompleteData completeData =
                    AnswerCompleteData.builder()
                            .protocolVersion(protocolVersion)
                            .contentLength(finalMarkdown.length())
                            .contentHash(contentHash)
                            .finishReason("STOP")
                            .requestedModelCode(requestedModelCode)
                            .effectiveModelCode(effectiveModelCode)
                            .fallbackUsed(fallbackUsed)
                            .build();

            AgentStreamEvent event = AgentStreamEvent.builder()
                            .runId(runId)
                            .messageId(messageId)
                            .eventId(eventId)
                            .sequence(currentSequence)
                            .type(eventType)
                            .content(protocolVersion == 1? "[DONE]": null)
                            .data(completeData)
                            .finishReason("STOP")
                            .contentLength(finalMarkdown.length())
                            .contentHash(contentHash)
                            .timestamp(System.currentTimeMillis())
                            .presentationType( finalPresentationType)
                            .presentationTitle(finalPresentationTitle)
                            .build();

            emitter.send(SseEmitter.event()
                            .id(eventId)
                            .name(eventName)
                            .data(event) );

            agentMetrics.recordSseEvent( protocolVersion,eventType );

            emitter.complete();
        } catch (Exception exception) {
            /*
             * 客户端已断开时，结束事件无法发送。
             * 直接标记取消并尝试正常结束，避免 completeWithError
             * 触发容器 onError 二次通知和全局异常日志。
             */
            if (isClientDisconnected(exception)) {
                closeReason = "CANCELLED";
                log.debug("SSE客户端已断开，跳过结束事件发送，runId={}", runId);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                    // 连接已断，忽略容器关闭异常。
                }
                return;
            }
            closeReason = "ERROR";

            /*
             * complete()已经取得结束权，
             * 这里直接通知容器异常关闭，不能再次调用error()。
             */
            emitter.completeWithError(exception);
            throw exception;
        } finally {
            agentMetrics.recordSseClosed(protocolVersion,closeReason );
        }
    }

    /**
     * 异常结束。
     */
    public synchronized void error(Throwable throwable) {
        if (!completed.compareAndSet(false,true )) {
            return;
        }
        /*
         * 客户端已断开时错误事件同样无法送达。
         * 不再调用 completeWithError，避免容器 onError 二次通知
         * 触发全局异常处理器的 ERROR 日志。
         */
        if (isClientDisconnected(throwable)) {
            log.debug("SSE客户端已断开，跳过错误事件发送，runId={}", runId);
            agentMetrics.recordSseClosed(protocolVersion,"CANCELLED");
            return;
        }
        try {
            long currentSequence =sequence.incrementAndGet();

            String eventId =runId + "-" + currentSequence;

            AgentStreamEvent event =AgentStreamEvent.builder()
                            .runId(runId)
                            .messageId(messageId)
                            .eventId(eventId)
                            .sequence(currentSequence)
                            .type(AgentStreamEventType.ERROR .name())
                            .content(safeErrorMessage(throwable))
                            .finishReason("ERROR")
                            .timestamp(System.currentTimeMillis())
                            .build();

            emitter.send(SseEmitter.event()
                            .id(eventId)
                            .name("error")
                            .data(event));
        } catch (Exception ignored) {
            /*
             * 客户端已经断开时，错误事件可能无法发送。
             * 此处不覆盖原始业务异常。
             */
        }finally {
            agentMetrics.recordSseEvent(protocolVersion,AgentStreamEventType.ERROR.name());
            agentMetrics.recordSseClosed(protocolVersion,"ERROR");
        }
        emitter.completeWithError(throwable);
    }

    /**
     * 客户端连接超时。
     */
    public void timeout() {
        error(new IllegalStateException( "Agent回答超时"));
    }

    /**
     * 标准化协议版本。
     */
    private int normalizeVersion( Integer requestedVersion,
            int defaultVersion) {
        if (requestedVersion != null && (requestedVersion == 1 || requestedVersion == 2)) {
            return requestedVersion;
        }
        return defaultVersion == 2 ? 2 : 1;
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
     * 获取当前已经累计的文字回答。
     */
    public synchronized String getFinalMarkdownSnapshot() {
        return finalMarkdown;
    }

    /**
     * 是否已经进入真实增量回答。
     */
    public synchronized boolean
    hasIncrementalAnswerStarted() {
        return incrementalAnswerStarted;
    }

    /**
     * SSE会话是否已经结束。
     */
    public boolean isCompleted() {
        return completed.get();
    }

    /**
     * 标记当前助手消息已经持久化。
     */
    public void markAssistantMessagePersisted() {
        assistantMessagePersisted.set(true);
    }

    /**
     * 当前助手消息是否已经持久化。
     */
    public boolean isAssistantMessagePersisted() {
        return assistantMessagePersisted.get();
    }
    /**
     * 客户端或容器提前关闭连接。
     */
    public void connectionClosed() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        agentMetrics.recordSseClosed( protocolVersion,"CANCELLED");
    }
    /**
     * 保存最终回答对应的模型信息。
     */
    public void setAnswerModelResult(
            String requestedModelCode,
            String effectiveModelCode) {

        this.requestedModelCode =
                normalizeModelCode(requestedModelCode);

        this.effectiveModelCode =
                normalizeModelCode(effectiveModelCode);

        this.fallbackUsed =
                this.requestedModelCode != null
                        && this.effectiveModelCode != null
                        && !this.requestedModelCode.equals(
                        this.effectiveModelCode
                );
    }

    /**
     * 统一清理模型编码。
     */
    private String normalizeModelCode(String modelCode) {
        if (modelCode == null || modelCode.isBlank()) {
            return null;
        }
        return modelCode.trim();
    }
}