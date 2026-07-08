package org.example.ai.agent.capability.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.CapabilitySaveDTO;
import org.example.ai.agent.capability.dto.CapabilityTestRequestDTO;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.mapper.CapabilityDefinitionMapper;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 能力定义 Service 实现。
 */
@Service
@RequiredArgsConstructor(onConstructor_ = {@Lazy, @Autowired})
public class CapabilityDefinitionServiceImpl extends ServiceImpl<CapabilityDefinitionMapper, CapabilityDefinition>
        implements CapabilityDefinitionService {

    private final BusinessCapabilityExecutor businessCapabilityExecutor;
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
        // 第一阶段只开放 READ，避免 Agent 自动执行写操作。
        if (StringUtils.hasText(dto.getSideEffect()) && !"READ".equalsIgnoreCase(dto.getSideEffect())) {
            throw new BusinessException(400, "第一阶段只允许配置 READ 查询类能力");
        }
        boolean exists = lambdaQuery()
                .eq(CapabilityDefinition::getCapabilityCode, dto.getCapabilityCode())
                .ne(dto.getId() != null, CapabilityDefinition::getId, dto.getId())
                .count() > 0;
        if (exists) {
            throw new BusinessException(400, "能力编码已存在：" + dto.getCapabilityCode());
        }
        dto.setOutputSchemaJson(cleanToSingleLine(dto.getOutputSchemaJson()));
        dto.setInputSchemaJson(cleanToSingleLine(dto.getInputSchemaJson()));
        CapabilityDefinition entity = new CapabilityDefinition();
        BeanUtils.copyProperties(dto, entity);
        // 默认启用，默认只读。
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        if (!StringUtils.hasText(entity.getSideEffect())) {
            entity.setSideEffect("READ");
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
    public CapabilityTestResultVO testCapability(String capabilityCode, CapabilityTestRequestDTO request) {
        if (!StringUtils.hasText(capabilityCode)) {
            throw new BusinessException(400, "能力编码不能为空");
        }

        // 构造一个最小 PlanStep，复用正式 Agent 的能力执行器。
        // ponytail: 不另写一套 HTTP 调用逻辑，测试路径和真实执行路径保持一致。
        PlanStep step = PlanStep.builder()
                .stepName("管理端测试调用")
                .capabilityCode(capabilityCode)
                .input(request == null ? new LinkedHashMap<>() : request.getInput())
                .outputKey("testResult")
                .build();

        ToolExecutionContext context = ToolExecutionContext.builder()
                .variables(new LinkedHashMap<>())
                .build();

        ToolResult result = businessCapabilityExecutor.execute(context, step);

        return CapabilityTestResultVO.builder()
                .success(result.isSuccess())
                .capabilityCode(result.getCapabilityCode())
                .input((Map<String, Object>) result.getInput())
                .data(result.getData())
                .fields(result.getFields())
                .summary(result.getSummary())
                .errorCode(result.getErrorCode())
                .errorMessage(result.getErrorMessage())
                .build();
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