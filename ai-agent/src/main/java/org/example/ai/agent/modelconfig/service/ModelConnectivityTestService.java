package org.example.ai.agent.modelconfig.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.common.enums.ModelCallType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.modelconfig.client.ModelClientFactory;
import org.example.ai.agent.modelconfig.model.ModelRuntimeConfig;
import org.example.ai.agent.modelconfig.vo.ModelTestResultVO;
import org.example.ai.agent.modelusage.model.TokenUsageData;
import org.example.ai.agent.modelusage.service.ModelUsageService;
import org.example.ai.agent.modelusage.support.TokenUsageExtractor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.LocalDateTime;
import java.util.concurrent.TimeoutException;

/**
 * 模型基础连接测试服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConnectivityTestService {

    private static final String TEST_PROMPT =
            "请只回复：模型连接正常";

    private static final int TEST_MAX_TOKENS = 32;

    private final ModelConfigService modelConfigService;
    private final ModelClientFactory modelClientFactory;
    private final ModelUsageService modelUsageService;
    private final TokenUsageExtractor tokenUsageExtractor;

    /**
     * 只测试指定模型，不执行备用模型切换。
     */
    public ModelTestResultVO test(String modelCode,String operator) {

        long startTime = System.currentTimeMillis();
        ModelRuntimeConfig config = null;

        ModelCallContext context = ModelCallContext.builder()
                .userId(operator)
                .callType(ModelCallType.MODEL_CONNECTIVITY_TEST)
                .modelCode(modelCode)
                .callSequence(1)
                .build();

        try {
            config = modelConfigService.loadRuntimeConfig(modelCode);

            ChatClient chatClient = modelClientFactory.create(config);

            ChatResponse response = chatClient.prompt()
                    .options(
                            ChatOptions.builder()
                                    .model(config.modelName())
                                    .temperature(0.0)
                                    .maxTokens(TEST_MAX_TOKENS)
                    )
                    .user(TEST_PROMPT)
                    .call()
                    .chatResponse();

            String responseText = extractResponseText(response);
            if (!StringUtils.hasText(responseText)) {
                throw new IllegalStateException(
                        "模型没有返回有效测试内容"
                );
            }

            long durationMs =System.currentTimeMillis() - startTime;

            String actualModelName = extractModelName(
                    response,
                    config.modelName()
            );
            recordSuccessSafely(
                    context,
                    config,
                    response,
                    durationMs,
                    actualModelName
            );

            modelConfigService.updateTestResult(
                    modelCode,
                    true,
                    "模型连接测试成功",
                    durationMs
            );

            return ModelTestResultVO.builder()
                    .modelCode(modelCode)
                    .success(true)
                    .provider(config.providerCode())
                    .modelName(actualModelName)
                    .durationMs(durationMs)
                    .responseText(limit(responseText, 100))
                    .errorCategory(null)
                    .message("模型连接测试成功")
                    .testedAt(LocalDateTime.now())
                    .build();
        } catch (Exception exception) {
            long durationMs =
                    System.currentTimeMillis() - startTime;

            String errorCategory = classifyError(exception);
            String safeMessage = buildSafeMessage(
                    errorCategory
            );

            recordFailureSafely(
                    context,
                    config,
                    durationMs,
                    safeMessage
            );

            updateTestResultSafely(
                    modelCode,
                    safeMessage,
                    durationMs
            );

            return ModelTestResultVO.builder()
                    .modelCode(modelCode)
                    .success(false)
                    .provider(
                            config == null
                                    ? null
                                    : config.providerCode()
                    )
                    .modelName(
                            config == null
                                    ? null
                                    : config.modelName()
                    )
                    .durationMs(durationMs)
                    .responseText("")
                    .errorCategory(errorCategory)
                    .message(safeMessage)
                    .testedAt(LocalDateTime.now())
                    .build();
        }
    }

    private String extractResponseText(
            ChatResponse response) {

        if (response == null
                || response.getResult() == null
                || response.getResult().getOutput() == null) {
            return null;
        }

        return response.getResult()
                .getOutput()
                .getText();
    }

    private String extractModelName(
            ChatResponse response,
            String configuredModelName) {

        if (response == null
                || response.getMetadata() == null
                || !StringUtils.hasText(
                response.getMetadata().getModel())) {
            return configuredModelName;
        }

        return response.getMetadata().getModel();
    }

    private void recordSuccessSafely(
            ModelCallContext context,
            ModelRuntimeConfig config,
            ChatResponse response,
            long durationMs,
            String actualModelName) {

        try {
            modelUsageService.recordSuccess(
                    context,
                    config.providerCode(),
                    actualModelName,
                    response.getMetadata() == null
                            ? null
                            : response.getMetadata().getId(),
                    tokenUsageExtractor.extract(response),
                    durationMs,
                    extractFinishReason(response)
            );
        } catch (Exception exception) {
            log.error(
                    "保存模型测试使用量失败，modelCode={}，错误={}",
                    config.modelCode(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private void recordFailureSafely(
            ModelCallContext context,
            ModelRuntimeConfig config,
            long durationMs,
            String safeMessage) {

        try {
            modelUsageService.recordFailure(
                    context,
                    config == null
                            ? null
                            : config.providerCode(),
                    config == null
                            ? null
                            : config.modelName(),
                    TokenUsageData.unknown(),
                    durationMs,
                    safeMessage
            );
        } catch (Exception exception) {
            log.error(
                    "保存模型测试失败记录异常，modelCode={}，错误={}",
                    context.getModelCode(),
                    exception.getMessage(),
                    exception
            );
        }
    }

    private void updateTestResultSafely(
            String modelCode,
            String safeMessage,
            long durationMs) {

        try {
            modelConfigService.updateTestResult(
                    modelCode,
                    false,
                    safeMessage,
                    durationMs
            );
        } catch (Exception exception) {
            log.error(
                    "更新模型测试状态失败，modelCode={}，错误={}",
                    modelCode,
                    exception.getMessage(),
                    exception
            );
        }
    }

    private String extractFinishReason(
            ChatResponse response) {

        if (response == null
                || response.getResult() == null
                || response.getResult().getMetadata() == null
                || response.getResult()
                .getMetadata()
                .getFinishReason() == null) {
            return null;
        }

        return String.valueOf(
                response.getResult()
                        .getMetadata()
                        .getFinishReason()
        );
    }

    /**
     * Phase 1只识别Java标准网络异常。
     *
     * 供应商HTTP状态的精确分类在Phase 4统一实现，
     * 这里不依赖不稳定的异常文本判断。
     */
    private String classifyError(Throwable throwable) {
        if (throwable instanceof BusinessException) {
            return "CONFIGURATION_ERROR";
        }

        if (hasCause(throwable, SocketTimeoutException.class)
                || hasCause(
                throwable,
                HttpTimeoutException.class)
                || hasCause(throwable, TimeoutException.class)) {
            return "TIMEOUT";
        }

        if (hasCause(throwable, ConnectException.class)
                || hasCause(
                throwable,
                UnknownHostException.class)) {
            return "CONNECTION_ERROR";
        }

        return "MODEL_CALL_FAILED";
    }

    private boolean hasCause(
            Throwable throwable,
            Class<? extends Throwable> causeType) {

        Throwable current = throwable;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }

        return false;
    }

    private String buildSafeMessage(String errorCategory) {
        return switch (errorCategory) {
            case "CONFIGURATION_ERROR" ->
                    "模型配置或加密密钥不正确";
            case "TIMEOUT" ->
                    "模型连接测试超时";
            case "CONNECTION_ERROR" ->
                    "无法连接模型服务地址";
            default ->
                    "模型调用失败，请检查地址、API Key和模型名称";
        };
    }

    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }

        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }
}