package org.example.ai.agent.capability.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.capability.dto.CapabilitySaveDTO;
import org.example.ai.agent.capability.dto.CapabilityTestRequestDTO;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.vo.AgentCapabilityVO;
import org.example.ai.agent.capability.vo.CapabilityDetailVO;
import org.example.ai.agent.capability.vo.CapabilityPublishResultVO;
import org.example.ai.agent.capability.vo.CapabilityTestResultVO;

import java.util.List;

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
     * 查询能力详情，包含字段字典。
     */
    CapabilityDetailVO detailWithFields(Long id);


    /**
     * 查询 Agent 可用能力清单。
     */
    List<AgentCapabilityVO> listEnabledForAgent();

    /**
     * 构建 Agent 可读的能力说明文本。
     *
     * 用于后续放进大模型提示词，让模型知道有哪些业务能力可选。
     */
    String buildEnabledCapabilitiesPrompt();

    /**
     * 审核并批量发布能力。
     *
     * 全部能力校验通过后才会统一发布。
     */
    CapabilityPublishResultVO publishCapabilities(List<String> capabilityCodes);

}