package org.example.ai.agent.capability.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.CapabilitySaveDTO;
import org.example.ai.agent.capability.dto.CapabilityTestRequestDTO;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.CapabilityDefinitionMapper;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.service.FieldDictionaryService;
import org.example.ai.agent.capability.vo.AgentCapabilityVO;
import org.example.ai.agent.capability.vo.CapabilityDetailVO;
import org.example.ai.agent.capability.vo.CapabilityTestResultVO;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 能力定义 Service 实现。
 */
@Service
@RequiredArgsConstructor
public class CapabilityDefinitionServiceImpl extends ServiceImpl<CapabilityDefinitionMapper, CapabilityDefinition>
        implements CapabilityDefinitionService {

    private final FieldDictionaryService fieldDictionaryService;
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

    @Override
    public Page<CapabilityDefinition> pageCapabilities(Page<CapabilityDefinition> page, String keyword, String domain, Integer enabled) {
        return baseMapper.pageCapabilities(page, keyword, domain, enabled);
    }

    @Override
    public Boolean saveCapability(CapabilitySaveDTO dto) {
//        checkRequired(dto);
        // 标准化副作用级别，未填写时默认为只读能力
        String sideEffect = StringUtils.hasText(dto.getSideEffect())
                ? dto.getSideEffect().trim().toUpperCase()
                : "READ";
        if (!List.of("READ", "WRITE", "DANGEROUS").contains(sideEffect)) {
            throw new BusinessException(400, "sideEffect 只允许配置 READ、WRITE 或 DANGEROUS");
        }
        dto.setSideEffect(sideEffect);
        if ("READ".equals(sideEffect)) {
            dto.setRequireConfirm(Boolean.FALSE);
        }
        // 写操作默认必须经过用户确认
        if ("WRITE".equals(sideEffect)) {
            dto.setRequireConfirm(Boolean.TRUE);
        }
        // 危险能力即使配置成功，后续执行器也必须拒绝执行
        if ("DANGEROUS".equals(sideEffect)) {
            dto.setRequireConfirm(Boolean.TRUE);
        }
        dto.setOutputSchemaJson(cleanToSingleLine(dto.getOutputSchemaJson()));
        dto.setInputSchemaJson(cleanToSingleLine(dto.getInputSchemaJson()));
        CapabilityDefinition entity = new CapabilityDefinition();
        BeanUtils.copyProperties(dto, entity);
        entity.setUpdatedAt(LocalDateTime.now());
        // 默认启用，默认只读。
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        return saveOrUpdate(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateEnabled(Long id, Integer enabled) {
        CapabilityDefinition entity = getById(id);
        if (entity == null) {
            throw new BusinessException(404, "能力不存在：" + id);
        }
        CapabilityDefinition update = new CapabilityDefinition();
        update.setId(id);
        update.setEnabled(enabled);
        return updateById(update);
    }

    @Override
    public CapabilityDetailVO detailWithFields(Long id) {
        CapabilityDetailVO vo = new CapabilityDetailVO();
        CapabilityDefinition capability = getById(id);
        if (capability == null) {
            throw new BusinessException(404, "能力不存在：" + id);
        }
        List<FieldDictionary> list = fieldDictionaryService.lambdaQuery()
                .eq(FieldDictionary::getCapabilityCode, capability.getCapabilityCode())
                .list();
        vo.setCapability(capability);
        vo.setFields(list);
        return vo;
    }

    @Override
    public List<AgentCapabilityVO> listEnabledForAgent() {
        return lambdaQuery()
                .eq(CapabilityDefinition::getEnabled, 1)
                .orderByAsc(CapabilityDefinition::getDomain)
                .orderByAsc(CapabilityDefinition::getCapabilityCode)
                .list()
                .stream()
                .map(item -> {
                    AgentCapabilityVO vo = new AgentCapabilityVO();
                    vo.setCapabilityCode(item.getCapabilityCode());
                    vo.setCapabilityName(item.getCapabilityName());
                    vo.setDomain(item.getDomain());
                    vo.setModuleName(item.getModuleName());
                    vo.setDescription(item.getDescription());
                    vo.setInputSchemaJson(item.getInputSchemaJson());
                    vo.setSideEffect(item.getSideEffect());
                    vo.setRequireConfirm(item.getRequireConfirm());
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    public String buildEnabledCapabilitiesPrompt() {
        List<CapabilityDefinition> capabilities = lambdaQuery()
                .eq(CapabilityDefinition::getEnabled, 1)
                .orderByAsc(CapabilityDefinition::getDomain)
                .orderByAsc(CapabilityDefinition::getCapabilityCode)
                .list();

        if (capabilities.isEmpty()) {
            return "当前没有可用业务能力。";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("【可用业务能力】\n");
        for (CapabilityDefinition item : capabilities) {
            builder.append("- 能力编码：").append(item.getCapabilityCode()).append("\n");
            builder.append("  能力名称：").append(item.getCapabilityName()).append("\n");
            builder.append("  适用场景：").append(item.getDescription()).append("\n");
            builder.append("  入参说明：").append(item.getInputSchemaJson()).append("\n");
            builder.append("  操作类型：")
                    .append(item.getSideEffect())
                    .append("\n");
            builder.append("  是否需要确认：")
                    .append(Boolean.TRUE.equals(item.getRequireConfirm()))
                    .append("\n");
        }
        // ponytail: 先生成纯文本，够 Agent 提示词使用；暂不做复杂 JSON Tool Schema。
        return builder.toString();
    }

    /**
     * 清理文本并移除所有换行符（转为空格）
     * 适用于需要单行显示的场景
     *
     * @param text 原始文本
     * @return 单行文本
     */
    public String cleanToSingleLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text
                .replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }
}