package org.example.ai.agent.modelusage.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.modelusage.model.TokenUsageData;
import org.example.ai.agent.modelusage.service.ModelUsageService;
import org.example.ai.agent.modelusage.support.TokenUsageExtractor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一模型调用服务。
 *
 * 所有业务代码都通过该服务调用模型，
 * 避免每个业务类重复编写 Token、耗时和异常记录逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultTrackedChatClientService
        implements TrackedChatClientService {

    private static final String PROVIDER_NAME = "openai-compatible";

    private final ChatClient chatClient;
    private final ModelUsageService modelUsageService;
    private final TokenUsageExtractor tokenUsageExtractor;

    /**
     * 同步模型调用。
     */
    @Override
    public ChatResponse call(ModelCallContext context,String systemPrompt, String userPrompt ) {
        long startTime = System.currentTimeMillis();
        TokenUsageData observedUsage = TokenUsageData.unknown();
        String observedModelName = null;
        try {
            ChatResponse response = chatClient.prompt()
                    .system(safePrompt(systemPrompt))
                    .user(safePrompt(userPrompt))
                    .call()
                    .chatResponse();
            /*
             * 即使后续内容检查失败，
             * 也先保留供应商已经返回的 Usage。
             */
            observedUsage = tokenUsageExtractor.extract(response);
            observedModelName = extractModelName(response);
            if (response == null || response.getResult() == null) {
                throw new IllegalStateException("模型没有返回有效响应");
            }

            String content = response.getResult().getOutput().getText();
            if (!StringUtils.hasText(content)) {
                throw new IllegalStateException("模型返回内容为空");
            }
            recordSuccessSafely(context, observedModelName,
                    extractRequestId(response),
                    observedUsage,
                    System.currentTimeMillis() - startTime,
                    extractFinishReason(response));

            return response;
        } catch (Exception exception) {
            recordFailureSafely(
                    context,
                    observedModelName,
                    observedUsage,
                    System.currentTimeMillis() - startTime,
                    exception.getMessage());
            throw exception;
        }
    }

    /**
     * 流式模型调用。
     *
     * 注意：
     * 流式响应的 Usage 通常只出现在最后一个响应块中，
     * 因此不能把每个响应块的 Token 相加，否则可能重复统计。
     */
    @Override
    public Flux<ChatResponse> stream(ModelCallContext context,String systemPrompt,String userPrompt) {
        /*
         * 每次订阅都创建独立的计时器和统计状态。
         *
         * 避免 retry、重复订阅时多次调用模型，
         * 却只保存一条 Token 明细。
         */
        return Flux.defer(() -> {
            long startTime = System.currentTimeMillis();

            AtomicBoolean usageRecorded = new AtomicBoolean(false);
            AtomicBoolean responseReceived = new AtomicBoolean(false);

            AtomicReference<TokenUsageData> bestUsage =
                    new AtomicReference<>(TokenUsageData.unknown());

            AtomicReference<String> modelName =
                    new AtomicReference<>();

            AtomicReference<String> requestId =
                    new AtomicReference<>();

            AtomicReference<String> finishReason =
                    new AtomicReference<>();

            final Flux<ChatResponse> responseFlux;

            try {
                responseFlux = chatClient.prompt()
                        .system(safePrompt(systemPrompt))
                        .user(safePrompt(userPrompt))
                        .stream()
                        .chatResponse();
            } catch (Exception exception) {
                recordFailureSafely(
                        context,
                        null,
                        TokenUsageData.unknown(),
                        System.currentTimeMillis() - startTime,
                        exception.getMessage()
                );
                return Flux.error(exception);
            }

            return responseFlux
                    .doOnNext(response -> {
                        if (response == null) {
                            return;
                        }

                        responseReceived.set(true);

                        TokenUsageData currentUsage =
                                tokenUsageExtractor.extract(response);

                        bestUsage.updateAndGet(existing ->
                                selectBetterUsage(existing, currentUsage));

                        setIfHasText(
                                modelName,
                                extractModelName(response)
                        );
                        setIfHasText(
                                requestId,
                                extractRequestId(response)
                        );
                        setIfHasText(
                                finishReason,
                                extractFinishReason(response)
                        );
                    })
                    .doOnComplete(() -> {
                        if (!usageRecorded.compareAndSet(false, true)) {
                            return;
                        }

                        /*
                         * 一个响应块都没有收到时，不能标记为成功。
                         */
                        if (!responseReceived.get()) {
                            recordFailureSafely(
                                    context,
                                    modelName.get(),
                                    bestUsage.get(),
                                    System.currentTimeMillis() - startTime,
                                    "模型流式调用未返回任何响应"
                            );
                            return;
                        }

                        recordSuccessSafely(
                                context,
                                modelName.get(),
                                requestId.get(),
                                bestUsage.get(),
                                System.currentTimeMillis() - startTime,
                                finishReason.get()
                        );
                    })
                    .doOnError(exception -> {
                        if (usageRecorded.compareAndSet(false, true)) {
                            recordFailureSafely(
                                    context,
                                    modelName.get(),
                                    bestUsage.get(),
                                    System.currentTimeMillis() - startTime,
                                    exception.getMessage()
                            );
                        }
                    })
                    .doFinally(signalType -> {
                        if (signalType == SignalType.CANCEL
                                && usageRecorded.compareAndSet(false, true)) {
                            recordFailureSafely(
                                    context,
                                    modelName.get(),
                                    bestUsage.get(),
                                    System.currentTimeMillis() - startTime,
                                    "模型流式调用被客户端取消"
                            );
                        }
                    });
        });
    }

    /**
     * 流式 Usage 不能直接累加。
     *
     * 优先保留 totalTokens 更大的响应，
     * 一般最后一个带 Usage 的响应块会被保留下来。
     */
    private TokenUsageData selectBetterUsage(
            TokenUsageData existing,
            TokenUsageData candidate) {
        if (candidate == null) {
            return existing == null ? TokenUsageData.unknown() : existing;
        }

        if (existing == null
                || candidate.getTotalTokens() >= existing.getTotalTokens()) {
            return candidate;
        }

        return existing;
    }

    private String extractModelName(ChatResponse response) {
        return response == null || response.getMetadata() == null
                ? null
                : response.getMetadata().getModel();
    }

    private String extractRequestId(ChatResponse response) {
        return response == null || response.getMetadata() == null
                ? null
                : response.getMetadata().getId();
    }

    private String extractFinishReason(ChatResponse response) {
        if (response == null
                || response.getResult() == null
                || response.getResult().getMetadata() == null
                || response.getResult().getMetadata().getFinishReason() == null) {
            return null;
        }

        return String.valueOf(
                response.getResult().getMetadata().getFinishReason()
        );
    }

    private void recordSuccessSafely(
            ModelCallContext context,
            String modelName,
            String requestId,
            TokenUsageData usage,
            long durationMs,
            String finishReason) {
        try {
            modelUsageService.recordSuccess(
                    context,
                    PROVIDER_NAME,
                    modelName,
                    requestId,
                    usage,
                    durationMs,
                    finishReason );
        } catch (Exception exception) {
            /*
             * Token 统计属于辅助功能。
             * 统计写入失败不能影响用户正常获得模型回答。
             */
            log.error(
                    "保存模型 Token 使用量失败，runId={}，错误={}",
                    context == null ? null : context.getRunId(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private void recordFailureSafely(
            ModelCallContext context,
            String modelName,
            TokenUsageData usage,
            long durationMs,
            String errorMessage) {
        try {
            modelUsageService.recordFailure(
                    context,
                    PROVIDER_NAME,
                    modelName,
                    usage,
                    durationMs,
                    errorMessage);
        } catch (Exception exception) {
            log.error(
                    "保存模型失败调用记录异常，runId={}，错误={}",
                    context == null ? null : context.getRunId(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private void setIfHasText( AtomicReference<String> target,String value) {
        if (StringUtils.hasText(value)) {
            target.set(value);
        }
    }

    private String safePrompt(String prompt) {
        return prompt == null ? "" : prompt;
    }
}