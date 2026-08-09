package org.example.ai.agent.modelconfig.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.ModelApiType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modelconfig.dto.ModelConfigSaveDTO;
import org.example.ai.agent.modelconfig.entity.ModelConfig;
import org.example.ai.agent.modelconfig.event.ModelConfigChangedEvent;
import org.example.ai.agent.modelconfig.mapper.ModelConfigMapper;
import org.example.ai.agent.modelconfig.model.ModelOption;
import org.example.ai.agent.modelconfig.model.ModelRuntimeConfig;
import org.example.ai.agent.modelconfig.security.ModelSecretCipher;
import org.example.ai.agent.modelconfig.service.ModelConfigService;
import org.example.ai.agent.modelconfig.vo.ModelConfigVO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 模型配置服务实现。
 */
@Service
@RequiredArgsConstructor
public class ModelConfigServiceImpl implements ModelConfigService {

    private static final Set<String> ALLOWED_URL_SCHEMES =
            Set.of("http", "https");

    private final ModelConfigMapper modelConfigMapper;
    private final ModelSecretCipher modelSecretCipher;
    private final ApplicationEventPublisher eventPublisher;
    @Override
    public List<ModelConfigVO> list() {
        return modelConfigMapper.selectList(
                        new LambdaQueryWrapper<ModelConfig>()
                                .orderByDesc(ModelConfig::getDefaultModel)
                                .orderByAsc(ModelConfig::getSortOrder)
                                .orderByAsc(ModelConfig::getId)
                )
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelConfigVO create(
            ModelConfigSaveDTO dto,
            String operator) {

        validateDto(dto);

        Long count = modelConfigMapper.selectCount(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(
                                ModelConfig::getModelCode,
                                dto.getModelCode()
                        )
        );

        if (count != null && count > 0) {
            throw new BusinessException(
                    ErrorCode.PROVIDER_ALREADY_EXISTS,
                    "模型编码已经存在：" + dto.getModelCode()
            );
        }

        if (!StringUtils.hasText(dto.getApiKey())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "新增模型时API Key不能为空"
            );
        }

        ModelConfig config = new ModelConfig();
        applyEditableFields(config, dto);
        config.setApiKeyCiphertext(
                modelSecretCipher.encrypt(dto.getApiKey())
        );
        config.setCreatedBy(operator);
        config.setUpdatedBy(operator);

        if (Boolean.TRUE.equals(dto.getDefaultModel())) {
            clearCurrentDefault(operator);
        }

        modelConfigMapper.insert(config);
        /*
         * 事务提交成功后只清理当前模型客户端，
         * 不影响其他已经缓存的模型。
         */
        publishConfigChanged(config.getModelCode());

        return toVO(requireByCode(dto.getModelCode()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ModelConfigVO update(
            String modelCode,
            ModelConfigSaveDTO dto,
            String operator) {

        validateDto(dto);

        if (!modelCode.equals(dto.getModelCode())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型编码保存后不允许修改"
            );
        }

        ModelConfig current = requireByCode(modelCode);

        if (isTrue(current.getDefaultModel())
                && !Boolean.TRUE.equals(dto.getDefaultModel())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "默认模型不能直接取消，请先将其他模型设为默认模型"
            );
        }

        applyEditableFields(current, dto);

        if (StringUtils.hasText(dto.getApiKey())) {
            current.setApiKeyCiphertext(
                    modelSecretCipher.encrypt(dto.getApiKey())
            );
        }

        current.setUpdatedBy(operator);

        if (Boolean.TRUE.equals(dto.getDefaultModel())) {
            clearCurrentDefault(operator);
        }

        modelConfigMapper.updateById(current);
        /*
         * 地址、密钥、模型名称或生成参数变化后，
         * 下次调用重新创建该模型客户端。
         */
        publishConfigChanged(modelCode);
        return toVO(requireByCode(modelCode));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(
            String modelCode,
            boolean enabled,
            String operator) {

        ModelConfig current = requireByCode(modelCode);

        if (!enabled && isTrue(current.getDefaultModel())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "系统默认模型不能停用，请先设置其他默认模型"
            );
        }

        modelConfigMapper.update(
                null,
                new LambdaUpdateWrapper<ModelConfig>()
                        .eq(ModelConfig::getId, current.getId())
                        .set(ModelConfig::getEnabled, enabled ? 1 : 0)
                        .set(ModelConfig::getUpdatedBy, operator)
        );
        /*
         * 停用模型后立即清理旧客户端，
         * 防止后续请求继续使用已经停用的模型。
         */
        publishConfigChanged(modelCode);
    }

    @Override
    public ModelRuntimeConfig loadRuntimeConfig(
            String modelCode) {

        if (!StringUtils.hasText(modelCode)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型编码不能为空"
            );
        }

        return findRuntimeConfig(modelCode)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PROVIDER_NOT_FOUND,
                        "模型配置不存在：" + modelCode
                ));
    }

    @Override
    public List<ModelOption> listEnabledOptions() {
        return modelConfigMapper.selectList(
                        new LambdaQueryWrapper<ModelConfig>()
                                .eq(ModelConfig::getEnabled, 1)
                                .orderByDesc(
                                        ModelConfig::getDefaultModel
                                )
                                .orderByAsc(ModelConfig::getSortOrder)
                                .orderByAsc(ModelConfig::getId)
                )
                .stream()
                .map(config -> new ModelOption(
                config.getModelCode(),
                config.getDisplayName(),
                config.getProviderCode(),
                isTrue(config.getDefaultModel()),
                isTrue(config.getStreamingSupported()),
                isTrue(config.getStructuredOutputSupported()),
                isTrue(config.getToolCallingSupported())
        )).toList();
    }

    @Override
    public Optional<ModelRuntimeConfig> findRuntimeConfig(String modelCode) {
        if (!StringUtils.hasText(modelCode)) {
            return Optional.empty();
        }

        ModelConfig config = modelConfigMapper.selectOne(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getModelCode,modelCode.trim())
                        .last("LIMIT 1")
        );

        return Optional.ofNullable(config).map(this::toRuntimeConfig);
    }

    @Override
    public Optional<ModelRuntimeConfig> findDefaultEnabledRuntimeConfig() {
        ModelConfig config = modelConfigMapper.selectOne(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(ModelConfig::getDefaultModel, 1)
                        .eq(ModelConfig::getEnabled, 1)
                        .orderByAsc(ModelConfig::getSortOrder)
                        .orderByAsc(ModelConfig::getId)
                        .last("LIMIT 1")
        );

        return Optional.ofNullable(config).map(this::toRuntimeConfig);
    }

    @Override
    public void updateTestResult(
            String modelCode,
            boolean success,
            String message,
            long durationMs) {

        modelConfigMapper.update(
                null,
                new LambdaUpdateWrapper<ModelConfig>()
                        .eq(ModelConfig::getModelCode, modelCode)
                        .set(
                                ModelConfig::getLastTestSuccess,
                                success ? 1 : 0
                        )
                        .set(
                                ModelConfig::getLastTestMessage,
                                limit(message, 255)
                        )
                        .set(
                                ModelConfig::getLastTestDurationMs,
                                Math.max(durationMs, 0)
                        )
                        .set(
                                ModelConfig::getLastTestAt,
                                LocalDateTime.now()
                        )
        );
    }

    private void validateDto(ModelConfigSaveDTO dto) {
        ModelApiType.from(dto.getApiType());
        validateBaseUrl(dto.getBaseUrl());

        if (Boolean.TRUE.equals(dto.getDefaultModel())
                && !Boolean.TRUE.equals(dto.getEnabled())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "系统默认模型必须处于启用状态"
            );
        }
    }

    private void validateBaseUrl(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl.trim());
            String scheme = uri.getScheme();

            if (!StringUtils.hasText(scheme)
                    || !ALLOWED_URL_SCHEMES.contains(
                    scheme.toLowerCase(Locale.ROOT))
                    || !StringUtils.hasText(uri.getHost())
                    || StringUtils.hasText(uri.getUserInfo())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "API地址必须是合法的HTTP或HTTPS地址"
            );
        }
    }

    private void applyEditableFields(
            ModelConfig config,
            ModelConfigSaveDTO dto) {

        config.setModelCode(dto.getModelCode().trim());
        config.setDisplayName(dto.getDisplayName().trim());
        config.setProviderCode(dto.getProviderCode().trim());
        config.setApiType(
                ModelApiType.from(dto.getApiType()).name()
        );
        config.setBaseUrl(dto.getBaseUrl().trim());
        config.setModelName(dto.getModelName().trim());
        config.setTemperature(dto.getTemperature());
        config.setMaxTokens(dto.getMaxTokens());
        config.setTimeoutSeconds(dto.getTimeoutSeconds());
        config.setStreamingSupported(
                Boolean.TRUE.equals(dto.getStreamingSupported())
                        ? 1 : 0
        );
        config.setStructuredOutputSupported(
                Boolean.TRUE.equals(
                        dto.getStructuredOutputSupported())
                        ? 1 : 0
        );
        config.setToolCallingSupported(
                Boolean.TRUE.equals(dto.getToolCallingSupported())
                        ? 1 : 0
        );
        config.setContextWindow(dto.getContextWindow());
        config.setDefaultModel(
                Boolean.TRUE.equals(dto.getDefaultModel())
                        ? 1 : 0
        );
        config.setEnabled(
                Boolean.TRUE.equals(dto.getEnabled())
                        ? 1 : 0
        );
        config.setSortOrder(dto.getSortOrder());
        config.setRemark(
                StringUtils.hasText(dto.getRemark())
                        ? dto.getRemark().trim()
                        : null
        );
    }

    private void clearCurrentDefault(String operator) {
        modelConfigMapper.update(
                null,
                new LambdaUpdateWrapper<ModelConfig>()
                        .eq(ModelConfig::getDefaultModel, 1)
                        .set(ModelConfig::getDefaultModel, 0)
                        .set(ModelConfig::getUpdatedBy, operator)
        );
    }

    private ModelConfig requireByCode(String modelCode) {
        if (!StringUtils.hasText(modelCode)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "模型编码不能为空"
            );
        }

        ModelConfig config = modelConfigMapper.selectOne(
                new LambdaQueryWrapper<ModelConfig>()
                        .eq(
                                ModelConfig::getModelCode,
                                modelCode
                        )
                        .last("LIMIT 1")
        );

        if (config == null) {
            throw new BusinessException(
                    ErrorCode.PROVIDER_NOT_FOUND,
                    "模型配置不存在：" + modelCode
            );
        }

        return config;
    }

    private ModelConfigVO toVO(ModelConfig config) {
        boolean keyConfigured = StringUtils.hasText(
                config.getApiKeyCiphertext()
        );

        return ModelConfigVO.builder()
                .id(config.getId())
                .modelCode(config.getModelCode())
                .displayName(config.getDisplayName())
                .providerCode(config.getProviderCode())
                .apiType(config.getApiType())
                .baseUrl(config.getBaseUrl())
                .apiKeyConfigured(keyConfigured)
                .apiKeyMasked(keyConfigured ? "******" : "")
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeoutSeconds(config.getTimeoutSeconds())
                .streamingSupported(
                        isTrue(config.getStreamingSupported())
                )
                .structuredOutputSupported(
                        isTrue(
                                config.getStructuredOutputSupported())
                )
                .toolCallingSupported(
                        isTrue(config.getToolCallingSupported())
                )
                .contextWindow(config.getContextWindow())
                .defaultModel(isTrue(config.getDefaultModel()))
                .enabled(isTrue(config.getEnabled()))
                .sortOrder(config.getSortOrder())
                .remark(config.getRemark())
                .lastTestSuccess(
                        toNullableBoolean(
                                config.getLastTestSuccess())
                )
                .lastTestMessage(config.getLastTestMessage())
                .lastTestDurationMs(
                        config.getLastTestDurationMs()
                )
                .lastTestAt(config.getLastTestAt())
                .createdBy(config.getCreatedBy())
                .updatedBy(config.getUpdatedBy())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }

    private boolean isTrue(Integer value) {
        return value != null && value == 1;
    }

    private Boolean toNullableBoolean(Integer value) {
        return value == null ? null : value == 1;
    }

    private String limit(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        return value.length() <= maxLength
                ? value
                : value.substring(0, maxLength);
    }
    /**
     * 数据库配置转换为服务端运行配置。
     *
     * API Key只在服务端内存中解密，
     * 禁止写入日志或者返回给前端。
     */
    private ModelRuntimeConfig toRuntimeConfig(
            ModelConfig config) {

        return new ModelRuntimeConfig(
                config.getModelCode(),
                config.getDisplayName(),
                config.getProviderCode(),
                config.getApiType(),
                config.getBaseUrl(),
                modelSecretCipher.decrypt(
                        config.getApiKeyCiphertext()
                ),
                config.getModelName(),
                config.getTemperature(),
                config.getMaxTokens(),
                config.getTimeoutSeconds(),
                isTrue(config.getStreamingSupported()),
                isTrue(config.getStructuredOutputSupported()),
                isTrue(config.getToolCallingSupported()),
                config.getContextWindow(),
                isTrue(config.getDefaultModel()),
                isTrue(config.getEnabled())
        );
    }

    /**
     * 发布配置变化事件。
     *
     * 监听器在当前事务提交成功后执行，
     * 保存失败时不会错误清理客户端缓存。
     */
    private void publishConfigChanged(String modelCode) {
        eventPublisher.publishEvent(
                new ModelConfigChangedEvent(modelCode)
        );
    }
}