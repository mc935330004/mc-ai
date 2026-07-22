package org.example.ai.agent.capability.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.CapabilitySaveDTO;
import org.example.ai.agent.capability.entity.BusinessSystem;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.CapabilityVersion;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.index.CapabilityIndexChangedEvent;
import org.example.ai.agent.capability.invocation.service.CapabilityBindingConfigurationService;
import org.example.ai.agent.capability.mapper.CapabilityDefinitionMapper;
import org.example.ai.agent.capability.service.BusinessSystemService;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.service.CapabilityVersionService;
import org.example.ai.agent.capability.service.FieldDictionaryService;
import org.example.ai.agent.capability.version.CapabilityRuntimeSnapshotResolver;
import org.example.ai.agent.capability.version.CapabilityVersionSnapshotFactory;
import org.example.ai.agent.capability.version.model.CapabilitySnapshotMaterial;
import org.example.ai.agent.capability.version.model.CapabilityVersionPublishResult;
import org.example.ai.agent.capability.vo.*;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
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
    private final ObjectMapper objectMapper;
    private final BusinessSystemService businessSystemService;
    private final ApplicationEventPublisher eventPublisher;
    private final CapabilityVersionSnapshotFactory snapshotFactory;
    private final CapabilityVersionService capabilityVersionService;
    private final CapabilityRuntimeSnapshotResolver runtimeSnapshotResolver;
    /**
     * 能力请求和响应绑定配置服务。
     */
    private final CapabilityBindingConfigurationService bindingConfigurationService;


    /**
     * 根据能力编码查询启用状态的能力。
     */
    @Override
    public CapabilityDefinition getEnabledByCode(String capabilityCode) {
        CapabilityDefinition registry =lambdaQuery().eq(CapabilityDefinition::getCapabilityCode,
                                capabilityCode)
                        .eq(CapabilityDefinition::getEnabled, 1)
                        .eq(CapabilityDefinition::getPublishStatus,
                                "PUBLISHED")
                        .isNotNull(CapabilityDefinition::getActiveVersionId)
                        .one();
        /*
         * 返回发布快照，而不是主表中的最新草稿。
         */
        return runtimeSnapshotResolver.resolve(registry);
    }

    @Override
    public Page<CapabilityDefinition> pageCapabilities(Page<CapabilityDefinition> page, String keyword, String domain, Integer enabled) {
        return baseMapper.pageCapabilities(page, keyword, domain, enabled);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveCapability(CapabilitySaveDTO dto) {
        checkSaveRequired(dto);

        CapabilityDefinition existing = null;

        /*
         * 修改能力时先读取旧记录：
         * 1. 检查记录是否存在；
         * 2. 计算下一个配置修订号。
         */
        if (dto.getId() != null) {
            existing = baseMapper.selectByIdForUpdate(dto.getId());

            if (existing == null) {
                throw new BusinessException(
                        404,
                        "能力不存在：" + dto.getId()
                );
            }
            /*
             * capabilityCode 是 GraphSpec 和版本记录的稳定引用，
             * 创建后禁止修改。
             */
            if (!existing.getCapabilityCode().equals(dto.getCapabilityCode().trim())) {
                throw new BusinessException( 400, "能力编码创建后不能修改" );
            }
        }

        String sideEffect =
                StringUtils.hasText(dto.getSideEffect())
                        ? dto.getSideEffect()
                        .trim()
                        .toUpperCase()
                        : "READ";

        if (!List.of(
                "READ",
                "WRITE",
                "DANGEROUS"
        ).contains(sideEffect)) {
            throw new BusinessException(
                    400,
                    "sideEffect只允许配置READ、WRITE或DANGEROUS"
            );
        }

        dto.setSideEffect(sideEffect);

        /*
         * READ 自动执行；
         * WRITE 必须确认；
         * DANGEROUS 即使保存成功也禁止发布。
         */
        if ("READ".equals(sideEffect)) {
            dto.setRequireConfirm(Boolean.FALSE);
        } else {
            dto.setRequireConfirm(Boolean.TRUE);
        }

        dto.setMethod(
                dto.getMethod().trim().toUpperCase()
        );

        dto.setUrl(dto.getUrl().trim());

        dto.setInputSchemaJson(
                cleanToSingleLine(dto.getInputSchemaJson())
        );

        dto.setOutputSchemaJson(
                cleanToSingleLine(dto.getOutputSchemaJson())
        );

        if (!StringUtils.hasText(
                dto.getRequestContentType()
        )) {
            dto.setRequestContentType(
                    "application/json"
            );
        }

        if (dto.getTimeoutMs() == null) {
            dto.setTimeoutMs(5000);
        }

        if (dto.getTimeoutMs() < 100
                || dto.getTimeoutMs() > 60000) {
            throw new BusinessException(
                    400,
                    "timeoutMs必须在100到60000毫秒之间"
            );
        }

        /*
         * 草稿阶段请求绑定可以为空；
         * 只要填写了，就必须同时通过：
         * 1. 请求绑定语法校验；
         * 2. inputSchemaJson 输入字段契约校验。
         */
        dto.setRequestBindingJson(
                bindingConfigurationService .normalizeRequestBinding(
                                dto.getCapabilityCode(),
                                dto.getMethod(),
                                dto.getUrl(),
                                dto.getInputSchemaJson(),
                                dto.getRequestBindingJson())
        );

        dto.setResponseBindingJson(bindingConfigurationService.normalizeResponseBinding( dto.getResponseBindingJson()));

        CapabilityDefinition entity = new CapabilityDefinition();

        BeanUtils.copyProperties(dto, entity);

        entity.setUpdatedAt(LocalDateTime.now());

        if (StringUtils.hasText(entity.getSystemCode())) {
            entity.setSystemCode(
                    entity.getSystemCode()
                            .trim()
                            .toUpperCase()
            );
        }

        if (!StringUtils.hasText(entity.getSourceType())) {
            entity.setSourceType("MANUAL");
        } else {
            entity.setSourceType(
                    entity.getSourceType()
                            .trim()
                            .toUpperCase()
            );
        }

        /*
         * 新增能力版本从1开始；
         * 已有能力每次保存配置时修订号加1。
         */
        int previousRevision = existing == null || existing.getConfigRevision() == null
                        ? 0 : existing.getConfigRevision();

        entity.setConfigRevision(previousRevision + 1);
        entity.setDraftDirty(1);

        if (existing == null || existing.getActiveVersionId() == null) {

            /*
             * 尚未发布过的能力。
             */
            entity.setActiveVersionId(null);
            entity.setPublishStatus("DRAFT");
            entity.setEnabled(0);
            entity.setConfigChecksum(null);
            entity.setValidatedAt(null);
        } else {
            /*
             * 已经发布过的能力继续保留原运行版本。
             *
             * 当前保存的只是新草稿，不得影响：
             * activeVersionId、enabled、configChecksum、validatedAt。
             */
            entity.setActiveVersionId(
                    existing.getActiveVersionId()
            );
            entity.setEnabled(existing.getEnabled());
            entity.setConfigChecksum(
                    existing.getConfigChecksum()
            );
            entity.setValidatedAt(
                    existing.getValidatedAt()
            );
            entity.setPublishStatus( "DISABLED".equals(existing.getPublishStatus())
                            ? "DISABLED"
                            : "PUBLISHED");
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
        boolean updated = updateById(update);

        if (updated) {
            eventPublisher.publishEvent( new CapabilityIndexChangedEvent( entity.getCapabilityCode()));
        }
        return updated;
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
        return listAgentCallableCapabilities()
                .stream()
                .map(item -> {
                    AgentCapabilityVO vo =new AgentCapabilityVO();
                    vo.setCapabilityCode(item.getCapabilityCode());
                    vo.setCapabilityName(item.getCapabilityName());
                    vo.setDomain(item.getDomain());
                    vo.setModuleName(item.getModuleName());
                    vo.setDescription(item.getDescription());
                    vo.setInputSchemaJson( item.getInputSchemaJson());
                    vo.setSideEffect(item.getSideEffect());
                    vo.setRequireConfirm(item.getRequireConfirm());
                    return vo;
                }).toList();
    }

    @Override
    public String buildEnabledCapabilitiesPrompt() {
        List<CapabilityDefinition> capabilities =listAgentCallableCapabilities();
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
    public CapabilityPublishResultVO publishCapabilities(List<String> capabilityCodes,String publishedBy) {
        if (capabilityCodes == null) {
            throw new BusinessException(
                    400,
                    "能力编码列表不能为空"
            );
        }

        List<String> uniqueCodes = capabilityCodes.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();

        if (uniqueCodes.isEmpty()) {
            throw new BusinessException(
                    400,
                    "能力编码列表不能为空"
            );
        }

        if (uniqueCodes.size() > 100) {
            throw new BusinessException(
                    400,
                    "一次最多发布100个能力"
            );
        }

        /*
         * 锁定所有待发布能力，防止两个发布请求生成相同版本号。
         */
        List<CapabilityDefinition> locked =
                baseMapper.selectByCodesForUpdate(uniqueCodes);

        Map<String, CapabilityDefinition> capabilityMap =
                locked.stream().collect(
                        Collectors.toMap(
                                CapabilityDefinition::getCapabilityCode,
                                item -> item
                        )
                );

        List<String> missingCodes = uniqueCodes.stream()
                .filter(code -> !capabilityMap.containsKey(code))
                .toList();

        if (!missingCodes.isEmpty()) {
            throw new BusinessException(
                    404,
                    "以下能力不存在：" + missingCodes
            );
        }

        List<CapabilityDefinition> capabilities =
                uniqueCodes.stream()
                        .map(capabilityMap::get)
                        .toList();

        /*
         * 第一遍只校验和生成快照。
         * 任意一个能力失败时，不允许写入任何版本。
         */
        Map<String, CapabilitySnapshotMaterial> materials =
                new LinkedHashMap<>();

        for (CapabilityDefinition capability : capabilities) {
            validateBeforePublish(capability);

            materials.put(
                    capability.getCapabilityCode(),
                    snapshotFactory.create(capability)
            );
        }

        LocalDateTime now = LocalDateTime.now();

        List<CapabilityPublishedVersionVO> versionViews =
                new java.util.ArrayList<>();

        int createdVersionCount = 0;
        int reusedVersionCount = 0;

        /*
         * 第二遍才执行版本写入。
         */
        for (CapabilityDefinition capability : capabilities) {
            CapabilitySnapshotMaterial material =
                    materials.get(
                            capability.getCapabilityCode()
                    );

            CapabilityVersionPublishResult result =
                    capabilityVersionService.publishSnapshot(
                            capability,
                            material,
                            publishedBy
                    );

            CapabilityVersion version = result.version();

            if (result.reused()) {
                reusedVersionCount++;
            } else {
                createdVersionCount++;
            }

            capability.setActiveVersionId(version.getId());
            capability.setConfigChecksum(
                    version.getConfigChecksum()
            );
            capability.setValidatedAt(now);
            capability.setDraftDirty(0);
            capability.setPublishStatus("PUBLISHED");
            capability.setEnabled(1);
            capability.setUpdatedAt(now);

            versionViews.add(
                    CapabilityPublishedVersionVO.builder()
                            .capabilityCode(
                                    capability.getCapabilityCode()
                            )
                            .versionId(version.getId())
                            .versionNo(version.getVersionNo())
                            .configRevision(
                                    version.getConfigRevision()
                            )
                            .configChecksum(
                                    version.getConfigChecksum()
                            )
                            .reused(result.reused())
                            .build()
            );
        }

        updateBatchById(capabilities);

        /*
         * 只有正式发布后才刷新 Agent 能力索引。
         */
        capabilities.forEach(capability ->eventPublisher.publishEvent(
                        new CapabilityIndexChangedEvent(capability.getCapabilityCode() )));

        return CapabilityPublishResultVO.builder()
                .submittedCount(uniqueCodes.size())
                .publishedCount(capabilities.size())
                .createdVersionCount(createdVersionCount)
                .reusedVersionCount(reusedVersionCount)
                .capabilityCodes(uniqueCodes)
                .versions(versionViews)
                .build();
    }

    @Override
    public List<CapabilityDefinition> listAgentCallableCapabilities() {
        List<CapabilityDefinition> registries =
                lambdaQuery()
                        .eq(CapabilityDefinition::getEnabled, 1)
                        .eq(CapabilityDefinition::getPublishStatus,
                                "PUBLISHED")
                        .isNotNull( CapabilityDefinition::getActiveVersionId)
                        .orderByAsc(CapabilityDefinition::getDomain)
                        .orderByAsc(CapabilityDefinition::getCapabilityCode)
                        .list();
        return registries.stream()
                .map(runtimeSnapshotResolver::resolve)
                .toList();

    }
    /**
     * 保存草稿时要求核心字段完整。
     *
     * Schema 和绑定配置可以在草稿阶段继续完善，
     * 但能力基本身份和目标接口必须明确。
     */
    private void checkSaveRequired(
            CapabilitySaveDTO dto) {

        if (dto == null) {
            throw new BusinessException(
                    400,
                    "能力保存参数不能为空"
            );
        }

        if (!StringUtils.hasText(dto.getCapabilityCode())) {
            throw new BusinessException(
                    400,
                    "capabilityCode不能为空"
            );
        }

        if (!StringUtils.hasText(dto.getCapabilityName())) {
            throw new BusinessException(
                    400,
                    "capabilityName不能为空"
            );
        }

        if (!StringUtils.hasText(dto.getDomain())) {
            throw new BusinessException(
                    400,
                    "domain不能为空"
            );
        }

        if (!StringUtils.hasText(dto.getMethod())) {
            throw new BusinessException(
                    400,
                    "method不能为空"
            );
        }

        if (!StringUtils.hasText(dto.getUrl())) {
            throw new BusinessException(
                    400,
                    "url不能为空"
            );
        }
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
        /*
         * 发布时必须重新校验数据库中的请求和响应绑定，
         * 不能只依赖保存阶段的校验结果。
         */
        bindingConfigurationService.validateForPublish(capability );

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