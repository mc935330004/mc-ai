package org.example.ai.agent.modelconfig.client;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.config.AgentModelProperties;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.modelconfig.event.ModelConfigChangedEvent;
import org.example.ai.agent.modelconfig.model.ModelRuntimeConfig;
import org.example.ai.agent.modelconfig.model.ResolvedModelClient;
import org.example.ai.agent.modelconfig.service.ModelConfigService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态模型客户端注册表。
 *
 * 负责模型客户端缓存、并发创建、配置失效和YAML回退，
 * 不负责自动故障转移。
 */
@Component
@RequiredArgsConstructor
public class ModelClientRegistry {

    /**
     * 即使配置绕过管理接口被直接修改，
     * 缓存也会在一分钟后重新加载。
     */
    private static final long CACHE_TTL_MS = 60_000L;


    private final ModelConfigService modelConfigService;
    private final ModelClientFactory modelClientFactory;
    private final AgentModelProperties yamlModelProperties;

    /**
     * Spring Boot根据YAML创建的原有客户端，
     * 仅作为迁移期兜底客户端。
     */
    private final ChatClient yamlChatClient;

    private final Map<String, CacheEntry> clientCache =
            new ConcurrentHashMap<>();

    private final Object defaultClientLock = new Object();

    private volatile CacheEntry defaultClientCache;

    /**
     * 根据调用类型和模型编码解析实际客户端。
     */
    public ResolvedModelClient resolve(
            ModelCallContext context) {

        String modelCode = resolveRequestedModelCode(context);

        if (StringUtils.hasText(modelCode)) {
            return resolveByCode(modelCode);
        }

        return resolveDefault();
    }

    /**
     * 按稳定模型编码解析客户端。
     */
    public ResolvedModelClient resolveByCode(
            String modelCode) {

        if (!StringUtils.hasText(modelCode)) {
            return resolveDefault();
        }

        String normalizedCode = modelCode.trim();
        long now = System.currentTimeMillis();

        CacheEntry current = clientCache.get(normalizedCode);
        if (isValid(current, now)) {
            return current.client();
        }

        Optional<ModelRuntimeConfig> runtimeConfig =
                modelConfigService.findRuntimeConfig(
                        normalizedCode
                );

        if (runtimeConfig.isPresent()) {
            return resolveDatabaseClient(
                    runtimeConfig.get(),
                    now
            );
        }

        return resolveYamlClient(normalizedCode);
    }

    /**
     * 解析系统默认客户端。
     *
     * 数据库不存在有效默认模型时，
     * 保留原有YAML默认客户端。
     */
    public ResolvedModelClient resolveDefault() {
        long now = System.currentTimeMillis();
        CacheEntry current = defaultClientCache;

        if (isValid(current, now)) {
            return current.client();
        }

        synchronized (defaultClientLock) {
            current = defaultClientCache;
            if (isValid(current, now)) {
                return current.client();
            }

            ResolvedModelClient resolvedClient =
                    modelConfigService
                            .findDefaultEnabledRuntimeConfig()
                            .map(config ->
                                    resolveDatabaseClient(
                                            config,
                                            now
                                    ))
                            .orElseGet(this::resolveYamlDefaultClient);

            defaultClientCache = new CacheEntry(
                    resolvedClient,
                    now + CACHE_TTL_MS
            );

            return resolvedClient;
        }
    }

    /**
     * 配置事务提交成功后只清理对应模型。
     */
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void handleModelConfigChanged(
            ModelConfigChangedEvent event) {

        if (event == null
                || !StringUtils.hasText(event.modelCode())) {
            return;
        }

        invalidate(event.modelCode());
    }

    public void invalidate(String modelCode) {
        if (StringUtils.hasText(modelCode)) {
            clientCache.remove(modelCode.trim());
        }

        /*
         * 默认模型可能发生切换，
         * 只清理默认引用，不清理其他模型客户端。
         */
        defaultClientCache = null;
    }

    private ResolvedModelClient resolveDatabaseClient(
            ModelRuntimeConfig config,
            long now) {

        requireEnabled(config);

        CacheEntry entry = clientCache.compute(
                config.modelCode(),
                (modelCode, current) -> {
                    if (isValid(current, now)) {
                        return current;
                    }

                    ResolvedModelClient client =
                            new ResolvedModelClient(
                                    config.modelCode(),
                                    config.providerCode(),
                                    config.modelName(),
                                    modelClientFactory.create(config)
                            );

                    return new CacheEntry(
                            client,
                            now + CACHE_TTL_MS
                    );
                }
        );

        return entry.client();
    }

    private ResolvedModelClient resolveYamlClient(
            String modelCode) {

        try {
            AgentModelProperties.ModelItem model =
                    yamlModelProperties.resolve(modelCode);

            return toYamlClient(model);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型不存在或已停用：" + modelCode
            );
        }
    }

    private ResolvedModelClient resolveYamlDefaultClient() {
        return toYamlClient(
                yamlModelProperties.defaultModel()
        );
    }

    private ResolvedModelClient toYamlClient(
            AgentModelProperties.ModelItem model) {

        String provider = StringUtils.hasText(
                model.getProvider()
        )
                ? model.getProvider()
                : "openai-compatible";

        return new ResolvedModelClient(
                model.getCode(),
                provider,
                model.getModelName(),
                yamlChatClient
        );
    }

    private String resolveRequestedModelCode(
            ModelCallContext context) {

        if (context == null || context.getCallType() == null
                || !context.getCallType() .usesUserSelectedModel()) {
            return null;
        }

        return context.getModelCode();
    }

    private void requireEnabled(
            ModelRuntimeConfig config) {

        if (!config.enabled()) {
            throw new BusinessException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "模型已经停用：" + config.modelCode()
            );
        }
    }

    private boolean isValid(
            CacheEntry entry,
            long now) {

        return entry != null
                && entry.expiresAtMs() > now;
    }

    /**
     * 客户端缓存项。
     */
    private record CacheEntry(
            ResolvedModelClient client,
            long expiresAtMs
    ) {
    }
}