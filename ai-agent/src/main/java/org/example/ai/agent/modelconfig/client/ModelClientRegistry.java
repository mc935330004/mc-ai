package org.example.ai.agent.modelconfig.client;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.common.modelusage.ModelCallContext;
import org.example.ai.agent.modelconfig.model.ModelRuntimeConfig;
import org.example.ai.agent.modelconfig.model.ResolvedModelClient;
import org.example.ai.agent.modelconfig.service.ModelConfigService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态模型客户端注册表。
 *
 * 模型运行配置只从数据库读取。
 * 本类负责客户端缓存、并发创建和配置失效，
 * 不负责授权判断和自动故障转移。
 */
@Component
@RequiredArgsConstructor
public class ModelClientRegistry {

    /**
     * 防止绕过管理接口直接修改数据库后，
     * 当前实例长期持有过期客户端。
     */
    private static final long CACHE_TTL_MS = 60_000L;

    private final ModelConfigService modelConfigService;
    private final ModelClientFactory modelClientFactory;

    /**
     * 按模型编码缓存动态创建的客户端。
     */
    private final Map<String, CacheEntry> clientCache =
            new ConcurrentHashMap<>();

    /**
     * 防止多个线程同时创建默认模型客户端。
     */
    private final Object defaultClientLock = new Object();

    private volatile CacheEntry defaultClientCache;

    /**
     * 根据调用上下文解析实际使用的模型客户端。
     */
    public ResolvedModelClient resolve(
            ModelCallContext context) {

        String modelCode =
                resolveRequestedModelCode(context);

        if (StringUtils.hasText(modelCode)) {
            return resolveByCode(modelCode);
        }

        return resolveDefault();
    }

    /**
     * 根据数据库模型编码解析客户端。
     */
    public ResolvedModelClient resolveByCode(
            String modelCode) {

        if (!StringUtils.hasText(modelCode)) {
            return resolveDefault();
        }

        String normalizedCode = modelCode.trim();
        long now = System.currentTimeMillis();

        CacheEntry current =
                clientCache.get(normalizedCode);

        if (isValid(current, now)) {
            return current.client();
        }

        ModelRuntimeConfig runtimeConfig =
                modelConfigService
                        .findRuntimeConfig(normalizedCode)
                        .orElseThrow(() ->
                                new BusinessException(
                                        ErrorCode.BAD_REQUEST,
                                        "模型不存在："
                                                + normalizedCode
                                ));

        return resolveDatabaseClient(
                runtimeConfig,
                now
        );
    }

    /**
     * 解析数据库中的默认启用模型。
     *
     * 数据库未配置可用默认模型时，
     * 只在实际模型调用时返回错误，不影响应用启动。
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

            ModelRuntimeConfig runtimeConfig =
                    modelConfigService
                            .findDefaultEnabledRuntimeConfig()
                            .orElseThrow(() ->
                                    new BusinessException(
                                            ErrorCode.AI_SERVICE_UNAVAILABLE,
                                            "当前没有已启用的默认聊天模型，"
                                                    + "请联系管理员配置"
                                    ));

            ResolvedModelClient resolvedClient =
                    resolveDatabaseClient(
                            runtimeConfig,
                            now
                    );

            defaultClientCache = new CacheEntry(
                    resolvedClient,
                    now + CACHE_TTL_MS
            );

            return resolvedClient;
        }
    }

    /**
     * 清理指定模型和默认模型的本地缓存。
     *
     * 该方法继续由本机事务事件和Redis失效消息调用。
     */
    public void invalidate(String modelCode) {
        if (StringUtils.hasText(modelCode)) {
            clientCache.remove(modelCode.trim());
        }

        /*
         * 模型修改时可能同时发生默认模型切换，
         * 因此每次都清理默认模型缓存。
         */
        defaultClientCache = null;
    }

    /**
     * 创建或复用数据库模型对应的客户端。
     */
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

    /**
     * 只有用户可以明确选择模型的调用类型，
     * 才读取请求中的模型编码。
     */
    private String resolveRequestedModelCode(
            ModelCallContext context) {

        if (context == null
                || context.getCallType() == null
                || !context.getCallType()
                .usesUserSelectedModel()) {
            return null;
        }

        return context.getModelCode();
    }

    private void requireEnabled(
            ModelRuntimeConfig config) {

        if (!config.enabled()) {
            throw new BusinessException(
                    ErrorCode.AI_SERVICE_UNAVAILABLE,
                    "模型已经停用："
                            + config.modelCode()
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
     * 本地模型客户端缓存项。
     */
    private record CacheEntry(
            ResolvedModelClient client,
            long expiresAtMs
    ) {
    }
}