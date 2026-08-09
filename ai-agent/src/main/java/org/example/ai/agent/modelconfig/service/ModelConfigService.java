package org.example.ai.agent.modelconfig.service;

import org.example.ai.agent.modelconfig.dto.ModelConfigSaveDTO;
import org.example.ai.agent.modelconfig.model.ModelOption;
import org.example.ai.agent.modelconfig.model.ModelRuntimeConfig;
import org.example.ai.agent.modelconfig.vo.ModelConfigVO;

import java.util.List;
import java.util.Optional;

/**
 * 模型配置服务。
 */
public interface ModelConfigService {

    List<ModelConfigVO> list();

    ModelConfigVO create(
            ModelConfigSaveDTO dto,
            String operator
    );

    ModelConfigVO update(
            String modelCode,
            ModelConfigSaveDTO dto,
            String operator
    );

    void updateStatus(
            String modelCode,
            boolean enabled,
            String operator
    );

    /**
     * 加载服务端运行时配置。
     *
     * 该方法允许读取停用模型，供管理员连接测试使用。
     */
    ModelRuntimeConfig loadRuntimeConfig(String modelCode);

    void updateTestResult(
            String modelCode,
            boolean success,
            String message,
            long durationMs
    );

    /**
     * 查询当前已经启用的模型。
     *
     * 只返回模型授权需要的安全字段。
     */
    List<ModelOption> listEnabledOptions();

    /**
     * 按模型编码查询运行时配置。
     *
     * 返回结果可能包含停用模型，
     * 由调用方根据使用场景决定是否允许使用。
     */
    Optional<ModelRuntimeConfig> findRuntimeConfig(
            String modelCode
    );

    /**
     * 查询数据库中已启用的系统默认模型。
     *
     * 没有有效默认模型时返回空，
     * 调用方继续回退YAML默认模型。
     */
    Optional<ModelRuntimeConfig> findDefaultEnabledRuntimeConfig();
}