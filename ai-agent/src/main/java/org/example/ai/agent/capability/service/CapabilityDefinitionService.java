package org.example.ai.agent.capability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.capability.entity.CapabilityDefinition;

/**
 * AI 能力定义 Service。
 */
public interface CapabilityDefinitionService extends IService<CapabilityDefinition> {

    /**
     * 根据能力编码查询已启用能力。
     *
     * @param capabilityCode 能力编码
     * @return 能力定义
     */
    CapabilityDefinition getEnabledByCode(String capabilityCode);
}