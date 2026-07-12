package org.example.ai.agent.capability.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.CapabilitySaveDTO;
import org.example.ai.agent.capability.dto.CapabilityTestRequestDTO;
import org.example.ai.agent.capability.entity.BusinessSystem;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.CapabilityDefinitionMapper;
import org.example.ai.agent.capability.service.BusinessSystemService;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.service.FieldDictionaryService;
import org.example.ai.agent.capability.vo.AgentCapabilityVO;
import org.example.ai.agent.capability.vo.CapabilityDetailVO;
import org.example.ai.agent.capability.vo.CapabilityPublishResultVO;
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
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AI 能力定义 Service 实现。
 */
@Service
@RequiredArgsConstructor
public class CapabilityDefinitionServiceImpl extends ServiceImpl<CapabilityDefinitionMapper, CapabilityDefinition>
        implements CapabilityDefinitionService {

    private final FieldDictionaryService fieldDictionaryService;
    private final ObjectMapper objectMapper;
    private final BusinessSystemService businessSystemService;
    /**
     * 根据能力编码查询启用状态的能力。
     */
    @Override
    public CapabilityDefinition getEnabledByCode(String capabilityCode) {
        return lambdaQuery()
                .eq(CapabilityDefinition::getCapabilityCode, capabilityCode)
                .eq(CapabilityDefinition::getEnabled, 1)
                // 兼容旧数据，同时禁止草稿能力被 Agent 调用。
                .and(wrapper -> wrapper
                        .eq(CapabilityDefinition::getPublishStatus,"PUBLISHED")
                        .or()
                        .isNull(CapabilityDefinition::getPublishStatus ))
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
        // 手工创建的能力默认标记为 MANUAL。
        if (!StringUtils.hasText(entity.getSourceType())) {
            entity.setSourceType("MANUAL");
        } else {
            entity.setSourceType(entity.getSourceType().trim().toUpperCase());
        }
        // 现有手工能力默认直接发布。
        // 后续通过 OpenAPI 导入的能力会明确设置为 DRAFT。
        if (!StringUtils.hasText(entity.getPublishStatus())) {
            entity.setPublishStatus("PUBLISHED");
        } else {
            entity.setPublishStatus(entity.getPublishStatus().trim().toUpperCase());
        }

        if (!List.of("DRAFT","PUBLISHED","DISABLED").contains(entity.getPublishStatus())) {
            throw new BusinessException(400,"publishStatus 只允许为 DRAFT、PUBLISHED 或 DISABLED");
        }
        if (StringUtils.hasText(entity.getSystemCode())) {
            entity.setSystemCode(entity.getSystemCode().trim().toUpperCase());
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
        if (!List.of(0, 1).contains(enabled)) {
            throw new BusinessException(400,"enabled只允许为0或1");
        }
        // 草稿能力必须通过发布接口，不能直接启用。
        if (enabled == 1 && !"PUBLISHED".equals(entity.getPublishStatus() )) {
            throw new BusinessException(400,"草稿能力不能直接启用，请先完成审核发布");
        }
        CapabilityDefinition update = new CapabilityDefinition();
        update.setId(id);
        update.setEnabled(enabled);
        update.setUpdatedAt(LocalDateTime.now());
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
                .and(wrapper -> wrapper
                        .eq(CapabilityDefinition::getPublishStatus,"PUBLISHED")
                        .or()
                        .isNull(CapabilityDefinition::getPublishStatus))
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CapabilityPublishResultVO publishCapabilities(List<String> capabilityCodes) {
        List<String> uniqueCodes = capabilityCodes.stream().filter(StringUtils::hasText).map(String::trim)
                .distinct().toList();
        if (uniqueCodes.isEmpty()) {
            throw new BusinessException( 400,"能力编码列表不能为空");
        }
        if (uniqueCodes.size() > 100) {
            throw new BusinessException( 400,"一次最多发布100个能力");
        }
        List<CapabilityDefinition> capabilities =
                lambdaQuery()
                        .in(CapabilityDefinition::getCapabilityCode,uniqueCodes)
                        .list();
        if (capabilities.size() != uniqueCodes.size()) {
            Set<String> existingCodes = capabilities.stream()
                    .map(CapabilityDefinition::getCapabilityCode)
                    .collect(Collectors.toSet());
            List<String> missingCodes = uniqueCodes.stream()
                    .filter(code ->!existingCodes.contains(code))
                    .toList();

            throw new BusinessException(404,"以下能力不存在：" + missingCodes);
        }

        // 先校验全部能力，再执行更新。
        for (CapabilityDefinition capability : capabilities) {
            validateBeforePublish(capability);
        }
        LocalDateTime now = LocalDateTime.now();
        capabilities.forEach(capability -> {
            capability.setPublishStatus("PUBLISHED");
            capability.setEnabled(1);
            capability.setUpdatedAt(now);
        });
        updateBatchById(capabilities);
        return CapabilityPublishResultVO.builder()
                .submittedCount(uniqueCodes.size())
                .publishedCount(capabilities.size())
                .capabilityCodes(uniqueCodes)
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

    /**
     * 能力发布前校验。
     */
    private void validateBeforePublish(CapabilityDefinition capability) {
        if (!StringUtils.hasText( capability.getCapabilityName())) {
            throw new BusinessException(400,"能力名称不能为空："+ capability.getCapabilityCode() );
        }

        if (!StringUtils.hasText(capability.getMethod())) {
            throw new BusinessException(400,"请求方法不能为空：" + capability.getCapabilityCode());
        }

        if (!StringUtils.hasText(capability.getUrl())) {
            throw new BusinessException(400, "接口地址不能为空："+ capability.getCapabilityCode() );
        }
        validateBusinessSystem(capability);
        validateSideEffect(capability);
        validateSchema(capability.getCapabilityCode(),"inputSchemaJson",capability.getInputSchemaJson());
        validateSchema(capability.getCapabilityCode(),"outputSchemaJson",capability.getOutputSchemaJson());

        // READ能力至少需要一个已经审核发布的返回字段。
        if ("READ".equalsIgnoreCase(capability.getSideEffect())) {
            long fieldCount = fieldDictionaryService.lambdaQuery()
                    .eq(FieldDictionary::getCapabilityCode,capability.getCapabilityCode())
                    .eq(FieldDictionary::getPublishStatus,"PUBLISHED").count();
            if (fieldCount == 0) {
                throw new BusinessException(400,"READ能力至少需要一个已发布字段字典："+ capability.getCapabilityCode());
            }
        }
    }

    /**
     * 校验能力所属业务系统。
     */
    private void validateBusinessSystem(CapabilityDefinition capability) {
        if (!StringUtils.hasText( capability.getSystemCode())) {
            // 兼容没有systemCode的历史能力。
            return;
        }
        BusinessSystem system =businessSystemService.getEnabledByCode(capability.getSystemCode());
        if (system == null) {
            throw new BusinessException(400,"能力所属业务系统不存在或未启用："+ capability.getSystemCode());
        }
    }

    /**
     * 校验能力副作用等级。
     */
    private void validateSideEffect(CapabilityDefinition capability) {
        String sideEffect = capability.getSideEffect();

        if (!List.of("READ","WRITE","DANGEROUS").contains(sideEffect)) {
            throw new BusinessException(400,"副作用等级不合法："+ capability.getCapabilityCode());
        }

        if ("READ".equals(sideEffect) && Boolean.TRUE.equals(capability.getRequireConfirm())) {
            throw new BusinessException(400,"READ能力不能要求用户确认："+ capability.getCapabilityCode());
        }

        if ("WRITE".equals(sideEffect) && !Boolean.TRUE.equals(capability.getRequireConfirm())) {
            throw new BusinessException(400,"WRITE能力必须要求用户确认："+ capability.getCapabilityCode());
        }
        // 当前版本禁止发布危险能力。
        if ("DANGEROUS".equals(sideEffect)) {
            throw new BusinessException(400,"DANGEROUS能力当前禁止发布："+ capability.getCapabilityCode());
        }
    }

    /**
     * 校验JSON Schema基本结构。
     */
    private void validateSchema(String capabilityCode, String fieldName, String schemaJson ) {
        if (!StringUtils.hasText(schemaJson)) {
            throw new BusinessException(400,fieldName + "不能为空："+ capabilityCode);
        }
        try {
            JsonNode root = objectMapper.readTree(schemaJson);
            if (!root.isObject()) {
                throw new BusinessException( 400,fieldName + "必须是JSON对象："+ capabilityCode);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(400,fieldName + "不是合法JSON：" + capabilityCode );
        }
    }
}