package org.example.ai.agent.capability.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.mapper.CapabilityDefinitionMapper;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.springframework.stereotype.Service;

/**
 * AI 能力定义 Service 实现。
 */
@Service
public class CapabilityDefinitionServiceImpl extends ServiceImpl<CapabilityDefinitionMapper, CapabilityDefinition>
        implements CapabilityDefinitionService {

    /**
     * 根据能力编码查询启用状态的能力。
     */
    @Override
    public CapabilityDefinition getEnabledByCode(String capabilityCode) {
        return lambdaQuery()
                .eq(CapabilityDefinition::getCapabilityCode, capabilityCode)
                .eq(CapabilityDefinition::getEnabled, 1)
                .one();
    }
}