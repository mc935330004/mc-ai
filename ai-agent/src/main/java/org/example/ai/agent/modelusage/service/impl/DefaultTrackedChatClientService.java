package org.example.ai.agent.modelusage.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.common.enums.ModelFailureCategory;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.common.modelusage.TrackedChatClientService;
import org.example.ai.agent.modelusage.model.TokenUsageData;
import org.example.ai.agent.modelusage.service.ModelUsageService;
import org.example.ai.agent.modelusage.support.TokenUsageExtractor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import org.example.ai.agent.modelconfig.client.ModelClientRegistry;
import org.example.ai.agent.modelconfig.model.ResolvedModelClient;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modelconfig.failover.ModelCandidateResolver;

import org.example.ai.agent.modelconfig.failover.ModelFailureClassifier;
import org.example.ai.agent.modelconfig.failover.ModelFailureTracker;

import java.util.List;
/**
 * 统一模型调用服务。
 *
 * 所有业务代码都通过该服务调用模型，
 * 避免每个业务类重复编写 Token、耗时和异常记录逻辑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultTrackedChatClientService implements TrackedChatClientService {

    /**
     * 根据模型编码获取实际客户端。
     */
    private final ModelClientRegistry modelClientRegistry;
    private final TokenUsageExtractor tokenUsageExtractor;
    /**
     * 根据模型编码获取实际客户端。
     */
    private final ModelUsageService modelUsageService;
    private final ModelCandidateResolver modelCandidateResolver;
    private final ModelFailureClassifier modelFailureClassifier;
    private final ModelFailureTracker modelFailureTracker;
    @Override
    public ChatResponse call(ModelCallContext context,String systemPrompt, String userPrompt ) {
        return call(context, systemPrompt, userPrompt,null );
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

            AtomicReference<String> finishReason =new AtomicReference<>();

            final Flux<ChatResponse> responseFlux;
            final ResolvedModelClient resolvedClient;
            final ModelCallContext usageContext;

            try {
                resolvedClient = modelClientRegistry.resolve(context);
                usageContext = copyResolvedContext(context,resolvedClient.modelCode(),1);

                /*
                 * 每个模型编码对应自己的地址、密钥和底层客户端。
                 */
                responseFlux = resolvedClient.chatClient()
                        .prompt()
                        .options(
                                buildModelOptions(
                                        resolvedClient,
                                        null
                                )
                        )
                        .system(safePrompt(systemPrompt))
                        .user(safePrompt(userPrompt))
                        .stream()
                        .chatResponse();

                modelName.set(resolvedClient.modelName());
            } catch (Exception exception) {
                recordFailureSafely(
                        context,
                        "unknown",
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
                                    usageContext,
                                    resolvedClient.providerCode(),
                                    modelName.get(),
                                    bestUsage.get(),
                                    System.currentTimeMillis() - startTime,
                                    "模型流式调用未返回任何响应"
                            );
                            return;
                        }

                        recordSuccessSafely(
                                usageContext,
                                resolvedClient.providerCode(),
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
                                    usageContext,
                                    resolvedClient.providerCode(),
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
                                    usageContext,
                                    resolvedClient.providerCode(),
                                    modelName.get(),
                                    bestUsage.get(),
                                    System.currentTimeMillis() - startTime,
                                    "模型流式调用被客户端取消"
                            );
                        }
                    });
        });
    }

    @Override
    public ChatResponse call(
            ModelCallContext context,
            String systemPrompt,
            String userPrompt,
            ChatOptions.Builder<?> optionsBuilder) {

        List<String> candidates = modelCandidateResolver.resolveCandidates(context,false);

        ModelFailureCategory lastCategory = null;
        int attemptSequence = 0;

        for (String modelCode : candidates) {
            /*
             * 当前模型处于短时故障状态时直接跳过，
             * 不产生新的供应商请求和Token费用。
             */
            if (modelFailureTracker.isBlocked(modelCode)) {
                continue;
            }

            attemptSequence++;

            ResolvedModelClient resolvedClient =modelClientRegistry.resolveByCode(modelCode);

            ModelCallContext usageContext =
                    copyResolvedContext(
                            context,
                            resolvedClient.modelCode(),
                            attemptSequence
                    );

            long startTime = System.currentTimeMillis();

            try {
                ChatResponse response = executeSyncCall(
                        resolvedClient,
                        systemPrompt,
                        userPrompt,
                        optionsBuilder
                );

                TokenUsageData usage =
                        tokenUsageExtractor.extract(response);

                String actualModelName =
                        extractModelName(response);

                if (!StringUtils.hasText(actualModelName)) {
                    actualModelName =
                            resolvedClient.modelName();
                }

                recordSuccessSafely(
                        usageContext,
                        resolvedClient.providerCode(),
                        actualModelName,
                        extractRequestId(response),
                        usage,
                        System.currentTimeMillis()
                                - startTime,
                        extractFinishReason(response)
                );

                modelFailureTracker.recordSuccess(
                        resolvedClient.modelCode()
                );

                return response;
            } catch (Exception exception) {
                ModelFailureCategory category =
                        modelFailureClassifier.classify(
                                exception
                        );

                recordFailureSafely(
                        usageContext,
                        resolvedClient.providerCode(),
                        resolvedClient.modelName(),
                        TokenUsageData.unknown(),
                        System.currentTimeMillis()
                                - startTime,
                        category.name(),
                        category.safeMessage()
                );

                if (!category.failoverAllowed()) {
                    throw propagate(exception);
                }
                modelFailureTracker.recordFailure(resolvedClient.modelCode(),category);
                lastCategory = category;
            }
        }

        String reason = lastCategory == null
                ? "当前候选模型均处于短时不可用状态"
                : lastCategory.safeMessage();

        throw new BusinessException(
                ErrorCode.AI_SERVICE_UNAVAILABLE,
                "本次请求未获得完整回答，"
                        + "已尝试模型数量："
                        + attemptSequence
                        + "，最终原因："
                        + reason
        );
    }
    /**
     * 执行一次同步模型调用。
     *
     * 本方法只负责单个模型，不包含重试或备用模型循环。
     */
    private ChatResponse executeSyncCall(
            ResolvedModelClient resolvedClient,
            String systemPrompt,
            String userPrompt,
            ChatOptions.Builder<?> optionsBuilder) {

        ChatResponse response = resolvedClient
                .chatClient()
                .prompt()
                .options(
                        buildModelOptions(
                                resolvedClient,
                                optionsBuilder
                        )
                )
                .system(safePrompt(systemPrompt))
                .user(safePrompt(userPrompt))
                .call()
                .chatResponse();

        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            throw new IllegalStateException(
                    "模型没有返回有效响应"
            );
        }

        String content = response.getResult()
                .getOutput()
                .getText();

        if (!StringUtils.hasText(content)) {
            throw new IllegalStateException(
                    "模型返回内容为空"
            );
        }

        return response;
    }

    private RuntimeException propagate(Exception exception) {

        if (exception instanceof RuntimeException runtimeException) {
            return runtimeException;
        }

        return new IllegalStateException(
                "模型调用失败",
                exception
        );
    }
    private void recordFailureSafely(
            ModelCallContext context,
            String providerCode,
            String modelName,
            TokenUsageData usage,
            long durationMs,
            String errorMessage) {

        recordFailureSafely(
                context,
                providerCode,
                modelName,
                usage,
                durationMs,
                null,
                errorMessage
        );
    }
    /**
     * 保存包含失败分类的模型调用记录。
     */
    private void recordFailureSafely(
            ModelCallContext context,
            String providerCode,
            String modelName,
            TokenUsageData usage,
            long durationMs,
            String errorCategory,
            String errorMessage) {

        try {
            modelUsageService.recordFailure(
                    context,
                    safeProvider(providerCode),
                    modelName,
                    usage,
                    durationMs,
                    errorCategory,
                    errorMessage
            );
        } catch (Exception exception) {
            /*
             * 使用量记录属于辅助功能，
             * 写入失败不能覆盖原始模型异常。
             */
            log.error(
                    "保存模型失败调用记录异常，runId={}，错误={}",
                    context == null
                            ? null
                            : context.getRunId(),
                    exception.getMessage(),
                    exception
            );
        }
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
            String providerCode,
            String modelName,
            String requestId,
            TokenUsageData usage,
            long durationMs,
            String finishReason) {
        try {
            modelUsageService.recordSuccess(
                    context,
                    safeProvider(providerCode),
                    modelName,
                    requestId,
                    usage,
                    durationMs,
                    finishReason
            );
        } catch (Exception exception) {
            /*
             * Token统计属于辅助功能，
             * 写入失败不能影响用户获得回答。
             */
            log.error(
                    "保存模型Token使用量失败，runId={}，错误={}",
                    context == null
                            ? null
                            : context.getRunId(),
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

    /**
     * 合并业务调用参数和实际模型名称。
     *
     * Planner传入的temperature、topP等参数继续保留，
     * 最终model由注册表解析后的客户端配置决定。
     */
    private ChatOptions.Builder<?> buildModelOptions(
            ResolvedModelClient resolvedClient,
            ChatOptions.Builder<?> optionsBuilder) {

        if (resolvedClient == null
                || !StringUtils.hasText(
                resolvedClient.modelName())) {
            throw new IllegalStateException(
                    "聊天模型的modelName未配置"
            );
        }

        ChatOptions.Builder<?> builder =
                optionsBuilder == null
                        ? ChatOptions.builder()
                        : optionsBuilder.clone();

        return builder.model(
                resolvedClient.modelName()
        );
    }
    /**
     * 使用实际模型编码创建本次统计上下文。
     *
     * 不直接修改原上下文，避免同一个上下文被并发调用时互相污染。
     */
    private ModelCallContext copyResolvedContext(
            ModelCallContext source,
            String resolvedModelCode,
            int attemptSequence) {

        if (source == null) {
            return ModelCallContext.builder()
                    .modelCode(resolvedModelCode)
                    .callSequence(1)
                    .attemptSequence(
                            Math.max(attemptSequence, 1)
                    )
                    .build();
        }

        return ModelCallContext.builder()
                .runId(source.getRunId())
                .conversationId(
                        source.getConversationId()
                )
                .userId(source.getUserId())
                .callType(source.getCallType())
                .modelCode(resolvedModelCode)
                .callSequence(
                        source.getCallSequence()
                )
                .attemptSequence(
                        Math.max(attemptSequence, 1)
                )
                .build();
    }

    private String safeProvider(String providerCode) {
        return StringUtils.hasText(providerCode)
                ? providerCode
                : "unknown";
    }
}