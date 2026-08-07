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
     * 最终完整 Markdown。
     */
    private volatile String finalMarkdown = "";
    /**
     * 中文注释：当前回答最终展示类型。
     */
    private volatile String finalPresentationType ="MARKDOWN";

    /**
     * 中文注释：当前回答报告标题。
     */
    private volatile String finalPresentationTitle;

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
        long currentSequence = sequence.incrementAndGet();

        String eventId = runId + "-" + currentSequence;

        event.setRunId(runId);
        event.setMessageId(messageId);
        event.setEventId(eventId);
        event.setSequence(currentSequence);
        event.setTimestamp(System.currentTimeMillis());
        emitter.send(SseEmitter.event().id(eventId).name(eventName) .data(event));
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
    public void publishAnswer(
            String markdown,
            String presentationType,
            String presentationTitle)
            throws Exception {
        finalMarkdown = markdown == null ? "": markdown;

        /*
         * 中文注释：
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
         * 中文注释：
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
     * 客户端或容器提前关闭连接。
     */
    public void connectionClosed() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        agentMetrics.recordSseClosed( protocolVersion,"CANCELLED");
    }
}