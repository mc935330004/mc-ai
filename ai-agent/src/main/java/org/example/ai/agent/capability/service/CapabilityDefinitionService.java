package org.example.ai.agent.capability.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.capability.dto.CapabilitySaveDTO;
import org.example.ai.agent.capability.dto.CapabilityTestRequestDTO;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.vo.CapabilityTestResultVO;

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

    /**
     * 分页查询能力列表。
     */
    Page<CapabilityDefinition> pageCapabilities(Page<CapabilityDefinition> page,String keyword,String domain,Integer enabled);

    /**
     * 新增或修改能力。
     */
    Boolean saveCapability(CapabilitySaveDTO dto);

    /**
     * 启用或停用能力。
     */
    Boolean updateEnabled(Long id, Integer enabled);

    /**
     * 测试调用某个能力。
     */
    CapabilityTestResultVO testCapability(String capabilityCode, CapabilityTestRequestDTO request);
}