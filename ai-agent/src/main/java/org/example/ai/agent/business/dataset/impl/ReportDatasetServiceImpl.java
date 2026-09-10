package org.example.ai.agent.business.dataset.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.business.dataset.ReportDatasetAdminAssembler;
import org.example.ai.agent.business.dataset.ReportDatasetAdminValidator;
import org.example.ai.agent.business.dataset.ReportDatasetService;
import org.example.ai.agent.business.dataset.dto.ReportDatasetSaveDTO;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.vo.ReportDatasetDetailVO;
import org.example.ai.agent.business.dataset.vo.ReportDatasetListVO;
import org.example.ai.agent.business.dataset.vo.ReportDatasetValidationVO;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.compiler.CompiledGraphSpec;
import org.example.ai.agent.graph.compiler.GraphCapabilityCatalog;
import org.example.ai.agent.graph.config.CapabilityNodeConfig;
import org.example.ai.agent.graph.config.CompiledForEachNodeConfig;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 报告数据集当前配置服务实现。
 *
 * 保存前失败关闭：工作流必须是当前可运行的发布版本，且主图和所有 FOREACH 子图都只能调用 READ 能力。
 */
@Service
@RequiredArgsConstructor
public class ReportDatasetServiceImpl
        extends ServiceImpl<ReportDatasetMapper, ReportDataset>
        implements ReportDatasetService {

    private final ReportDatasetMapper datasetMapper;
    private final ReportDatasetFieldMapper fieldMapper;
    private final WorkflowRuntimeSnapshotResolver workflowResolver;
    private final GraphCapabilityCatalog capabilityCatalog;
    private final ObjectMapper objectMapper;
    private final ReportDatasetAdminAssembler adminAssembler;
    private final ReportDatasetAdminValidator adminValidator;

    /**
     * 管理端分页只查询当前配置，并用一次字段查询统计当前页字段数量。
     */
    @Override
    @Transactional(readOnly = true)
    public Page<ReportDatasetListVO> pageCurrent(
            long current,
            long size,
            String keyword,
            String domainCode,
            Boolean enabled) {
        if (current < 1 || size < 1 || size > 100) {
            throw new BusinessException(400, "分页参数不合法");
        }
        var query = Wrappers.<ReportDataset>lambdaQuery();
        if (StringUtils.hasText(keyword)) {
            String value = keyword.trim();
            query.and(item -> item
                    .like(ReportDataset::getDatasetCode, value)
                    .or()
                    .like(ReportDataset::getDatasetName, value));
        }
        if (StringUtils.hasText(domainCode)) {
            query.eq(ReportDataset::getDomainCode, domainCode.trim());
        }
        if (enabled != null) {
            query.eq(ReportDataset::getEnabled, enabled);
        }
        query.orderByDesc(ReportDataset::getUpdatedAt)
                .orderByDesc(ReportDataset::getId);

        Page<ReportDataset> source = datasetMapper.selectPage(
                new Page<>(current, size),
                query
        );
        List<ReportDataset> datasets = source.getRecords() == null
                ? List.of()
                : source.getRecords();
        Map<Long, Integer> fieldCounts = loadFieldCounts(datasets);
        List<ReportDatasetListVO> records = datasets.stream()
                .map(dataset -> adminAssembler.toListView(
                        dataset,
                        fieldCounts.getOrDefault(dataset.getId(), 0)
                ))
                .toList();
        Page<ReportDatasetListVO> result = new Page<>(current, size, source.getTotal());
        result.setRecords(records);
        return result;
    }

    /** 查询详情时按展示顺序返回字段，页面无需再次排序。 */
    @Override
    @Transactional(readOnly = true)
    public ReportDatasetDetailVO detailCurrent(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(400, "数据集ID不合法");
        }
        ReportDataset dataset = datasetMapper.selectById(id);
        if (dataset == null) {
            throw new BusinessException(404, "报告数据集不存在");
        }
        List<ReportDatasetField> fields = fieldMapper.selectList(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .eq(ReportDatasetField::getDatasetId, id)
                        .orderByAsc(
                                ReportDatasetField::getDisplayOrder,
                                ReportDatasetField::getId
                        )
        );
        return adminAssembler.toDetailView(
                dataset,
                fields == null ? List.of() : fields
        );
    }

    /**
     * 管理端校验只读取工作流和字段元数据，不执行真实业务查询。
     */
    @Override
    @Transactional(readOnly = true)
    public ReportDatasetValidationVO validateCurrent(ReportDatasetSaveDTO dto) {
        prepare(dto);
        return new ReportDatasetValidationVO(true, "配置校验通过");
    }

    /**
     * 管理端新增和编辑共用同一套结构化校验；编辑时数据集编码不可修改。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDatasetDetailVO saveCurrent(
            ReportDatasetSaveDTO dto,
            String operatorId) {
        String operator = requireText(operatorId, "操作人不能为空");
        if (dto == null) {
            throw new BusinessException(400, "数据集配置不能为空");
        }

        ReportDataset existing = null;
        if (dto.getId() != null) {
            if (dto.getId() <= 0) {
                throw new BusinessException(400, "数据集ID不合法");
            }
            existing = datasetMapper.selectById(dto.getId());
            if (existing == null) {
                throw new BusinessException(404, "报告数据集不存在");
            }
            String datasetCode = requireText(dto.getDatasetCode(), "datasetCode不能为空");
            if (!datasetCode.equals(existing.getDatasetCode())) {
                throw new BusinessException(400, "数据集编码创建后不能修改");
            }
            if (dto.getVersion() == null) {
                throw new BusinessException(400, "更新当前数据集配置时version不能为空");
            }
            if (!Objects.equals(dto.getVersion(), existing.getVersion())) {
                throw new BusinessException(409, "报告数据集配置已被其他人修改，请刷新后重试");
            }
        }

        PreparedConfiguration prepared = prepare(dto);
        if (existing == null) {
            ReportDataset sameCode = findByCode(prepared.dataset().getDatasetCode());
            if (sameCode != null) {
                throw new BusinessException(409, "相同datasetCode的当前配置已经存在");
            }
        }
        persistConfiguration(prepared, existing, operator);
        return adminAssembler.toDetailView(prepared.dataset(), prepared.fields());
    }

    /**
     * 启停只提交必要字段，避免把旧页面数据覆盖到其它配置列。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(
            Long id,
            Boolean enabled,
            Integer version,
            String operatorId) {
        if (id == null || id <= 0) {
            throw new BusinessException(400, "数据集ID不合法");
        }
        if (enabled == null) {
            throw new BusinessException(400, "enabled不能为空");
        }
        if (version == null || version < 0) {
            throw new BusinessException(400, "version不合法");
        }
        String operator = requireText(operatorId, "操作人不能为空");
        ReportDataset existing = datasetMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "报告数据集不存在");
        }
        if (!Objects.equals(version, existing.getVersion())) {
            throw new BusinessException(409, "报告数据集配置已被其他人修改，请刷新后重试");
        }

        ReportDataset update = new ReportDataset();
        update.setId(id);
        update.setEnabled(enabled);
        update.setVersion(version);
        update.setUpdatedBy(operator);
        update.setUpdatedAt(LocalDateTime.now());
        if (datasetMapper.updateById(update) != 1) {
            throw new BusinessException(409, "报告数据集配置已被其他人修改，请刷新后重试");
        }
    }

    private PreparedConfiguration prepare(ReportDatasetSaveDTO dto) {
        if (dto == null) {
            throw new BusinessException(400, "数据集配置不能为空");
        }
        ReportDataset dataset = adminAssembler.toDataset(dto);
        List<ReportDatasetField> fields = adminAssembler.toFields(dto.getFields());
        validateDataset(dataset);
        List<ReportDatasetField> validatedFields = validateFields(fields);
        JsonNode subjectTypes = normalizeSubjectTypesJson(dataset.getSubjectTypesJson());
        JsonNode inputMapping = normalizeInputMappingJson(dataset.getInputMappingJson());
        dataset.setSubjectTypesJson(writeJson(subjectTypes));
        dataset.setInputMappingJson(writeJson(inputMapping));
        adminValidator.validate(dataset, validatedFields);
        return new PreparedConfiguration(
                dataset,
                validatedFields,
                subjectTypes,
                inputMapping
        );
    }

    private record PreparedConfiguration(
            ReportDataset dataset,
            List<ReportDatasetField> fields,
            JsonNode subjectTypes,
            JsonNode inputMapping) {
    }

    private Map<Long, Integer> loadFieldCounts(List<ReportDataset> datasets) {
        List<Long> ids = datasets.stream()
                .map(ReportDataset::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<ReportDatasetField> fields = fieldMapper.selectList(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .in(ReportDatasetField::getDatasetId, ids)
        );
        Map<Long, Integer> counts = new HashMap<>();
        if (fields != null) {
            for (ReportDatasetField field : fields) {
                if (field != null && field.getDatasetId() != null) {
                    counts.merge(field.getDatasetId(), 1, Integer::sum);
                }
            }
        }
        return counts;
    }


    /**
     * 同一 datasetCode 更新当前行，不创建配置历史；字段策略在同一事务内整体替换。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportDataset saveCurrent(
            ReportDataset dataset,
            List<ReportDatasetField> fields,
            String operatorId) {
        String operator = requireText(operatorId, "操作人不能为空");
        validateDataset(dataset);
        List<ReportDatasetField> validatedFields = validateFields(fields);

        JsonNode subjectTypes = normalizeSubjectTypesJson(dataset.getSubjectTypesJson());
        JsonNode inputMapping = normalizeInputMappingJson(dataset.getInputMappingJson());
        dataset.setSubjectTypesJson(writeJson(subjectTypes));
        dataset.setInputMappingJson(writeJson(inputMapping));

        PublishedWorkflow queryWorkflow = workflowResolver.resolveByCode(
                dataset.getQueryWorkflowCode()
        );
        validateReadOnlyWorkflow(dataset.getQueryWorkflowCode(), queryWorkflow);

        if (StringUtils.hasText(dataset.getAccessWorkflowCode())) {
            PublishedWorkflow accessWorkflow = workflowResolver.resolveByCode(
                    dataset.getAccessWorkflowCode()
            );
            validateReadOnlyWorkflow(dataset.getAccessWorkflowCode(), accessWorkflow);
        }

        ReportDataset existing = findByCode(dataset.getDatasetCode());
        persistConfiguration(
                new PreparedConfiguration(
                        dataset,
                        validatedFields,
                        subjectTypes,
                        inputMapping
                ),
                existing,
                operator
        );
        return dataset;
    }

    private ReportDataset findByCode(String datasetCode) {
        return datasetMapper.selectOne(
                Wrappers.<ReportDataset>lambdaQuery()
                        .eq(ReportDataset::getDatasetCode, datasetCode)
        );
    }

    private void persistConfiguration(
            PreparedConfiguration prepared,
            ReportDataset existing,
            String operator) {
        ReportDataset dataset = prepared.dataset();
        dataset.setConfigChecksum(buildConfigChecksum(
                dataset,
                prepared.subjectTypes(),
                prepared.inputMapping()
        ));
        dataset.setFieldPolicyChecksum(buildFieldPolicyChecksum(prepared.fields()));
        LocalDateTime now = LocalDateTime.now();
        persistDataset(dataset, existing, operator, now);
        replaceFields(dataset.getId(), prepared.fields(), operator, now);
    }

    private void validateDataset(ReportDataset dataset) {
        if (dataset == null) {
            throw new BusinessException(400, "数据集配置不能为空");
        }
        dataset.setDatasetCode(requireText(dataset.getDatasetCode(), "datasetCode不能为空"));
        dataset.setDatasetName(requireText(dataset.getDatasetName(), "datasetName不能为空"));
        dataset.setDomainCode(requireText(dataset.getDomainCode(), "domainCode不能为空"));
        dataset.setSubjectTypesJson(requireText(dataset.getSubjectTypesJson(), "subjectTypesJson不能为空"));
        dataset.setQueryWorkflowCode(requireText(dataset.getQueryWorkflowCode(), "queryWorkflowCode不能为空"));
        dataset.setAccessWorkflowCode(trimToNull(dataset.getAccessWorkflowCode()));
        dataset.setInputMappingJson(requireText(dataset.getInputMappingJson(), "inputMappingJson不能为空"));
        dataset.setAssociationMode(requireText(dataset.getAssociationMode(), "associationMode不能为空"));

        if (dataset.getTtlMinutes() == null
                || dataset.getTtlMinutes() < 1
                || dataset.getTtlMinutes() > 1440) {
            throw new BusinessException(400, "数据集TTL必须在1..1440分钟之间");
        }
        if (dataset.getMaxConcurrency() == null || dataset.getMaxConcurrency() <= 0) {
            throw new BusinessException(400, "maxConcurrency必须大于0");
        }
        if (dataset.getEnabled() == null) {
            throw new BusinessException(400, "enabled不能为空");
        }
    }

    private List<ReportDatasetField> validateFields(List<ReportDatasetField> fields) {
        if (fields == null || fields.isEmpty()) {
            throw new BusinessException(400, "数据集字段策略不能为空");
        }

        Set<String> factCodes = new LinkedHashSet<>();
        List<ReportDatasetField> result = new ArrayList<>(fields.size());
        for (ReportDatasetField field : fields) {
            if (field == null) {
                throw new BusinessException(400, "数据集字段策略不能包含空项");
            }
            if (field.getFieldId() == null) {
                throw new BusinessException(400, "fieldId不能为空");
            }
            field.setFactCode(requireText(field.getFactCode(), "factCode不能为空"));
            field.setFactName(requireText(field.getFactName(), "factName不能为空"));
            field.setFactType(requireText(field.getFactType(), "factType不能为空"));
            field.setMaskStrategy(requireText(field.getMaskStrategy(), "maskStrategy不能为空"));
            field.setGrain(requireText(field.getGrain(), "grain不能为空"));
            if (!factCodes.add(field.getFactCode())) {
                throw new BusinessException(400, "同一数据集factCode重复：" + field.getFactCode());
            }
            requirePolicyFlag(field.getCalculable(), "calculable");
            requirePolicyFlag(field.getDisplayable(), "displayable");
            requirePolicyFlag(field.getExportable(), "exportable");
            requirePolicyFlag(field.getModelVisible(), "modelVisible");
            requirePolicyFlag(field.getFilterable(), "filterable");
            if (field.getDisplayOrder() == null || field.getDisplayOrder() < 0) {
                throw new BusinessException(400, "displayOrder不能小于0");
            }
            result.add(field);
        }
        return result;
    }

    private void requirePolicyFlag(Boolean value, String name) {
        if (value == null) {
            throw new BusinessException(400, name + "不能为空");
        }
    }

    private JsonNode normalizeSubjectTypesJson(String json) {
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (parsed == null || !parsed.isArray() || parsed.isEmpty()) {
                throw new BusinessException(400, "subjectTypesJson必须是非空JSON数组");
            }

            Set<BusinessSubjectType> subjectTypes = EnumSet.noneOf(BusinessSubjectType.class);
            for (JsonNode item : parsed) {
                if (!item.isTextual() || !StringUtils.hasText(item.textValue())) {
                    throw new BusinessException(400, "主体类型只能是非空字符串");
                }
                String subjectTypeCode = item.textValue().trim();
                BusinessSubjectType subjectType;
                try {
                    subjectType = BusinessSubjectType.valueOf(subjectTypeCode);
                } catch (IllegalArgumentException exception) {
                    throw new BusinessException(400, "不支持的主体类型：" + subjectTypeCode);
                }
                if (!subjectTypes.add(subjectType)) {
                    throw new BusinessException(400, "主体类型重复：" + subjectTypeCode);
                }
            }

            ArrayNode normalized = objectMapper.createArrayNode();
            for (BusinessSubjectType subjectType : BusinessSubjectType.values()) {
                if (subjectTypes.contains(subjectType)) {
                    normalized.add(subjectType.name());
                }
            }
            return normalized;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(400, "subjectTypesJson不是合法JSON");
        }
    }

    private JsonNode normalizeInputMappingJson(String json) {
        try {
            JsonNode parsed = objectMapper.readTree(json);
            if (parsed == null || !parsed.isObject()) {
                throw new BusinessException(400, "inputMappingJson必须是JSON对象");
            }
            return canonicalize(parsed);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(400, "inputMappingJson不是合法JSON");
        }
    }

    /**
     * 递归扫描编译图，FOREACH body 也必须满足 READ 边界。
     */
    private void validateReadOnlyWorkflow(
            String workflowCode,
            PublishedWorkflow workflow) {
        if (workflow == null || workflow.compiledGraph() == null) {
            throw new BusinessException(409, "已发布工作流缺少可执行编译图：" + workflowCode);
        }
        validateReadOnlyGraph(workflowCode, workflow.compiledGraph());
    }

    private void validateReadOnlyGraph(
            String workflowCode,
            CompiledGraphSpec graph) {
        if (graph == null || graph.nodesById() == null) {
            return;
        }
        for (CompiledGraphNode node : graph.nodesById().values()) {
            if (node == null) {
                continue;
            }
            if (node.config() instanceof CapabilityNodeConfig capability
                    && StringUtils.hasText(capability.capabilityCode())) {
                String capabilityCode = capability.capabilityCode().trim();
                String sideEffect = trimToNull(capabilityCatalog.sideEffect(capabilityCode));
                if (!"READ".equalsIgnoreCase(sideEffect)) {
                    throw new BusinessException(
                            400,
                            "报告数据集只允许引用READ工作流，工作流"
                                    + workflowCode
                                    + "包含非READ能力："
                                    + capabilityCode
                    );
                }
            }
            if (node.config() instanceof CompiledForEachNodeConfig forEach
                    && forEach.body() != null) {
                validateReadOnlyGraph(workflowCode, forEach.body());
            }
        }
    }

    private String buildConfigChecksum(
            ReportDataset dataset,
            JsonNode subjectTypes,
            JsonNode inputMapping) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("datasetCode", dataset.getDatasetCode());
        canonical.put("datasetName", dataset.getDatasetName());
        canonical.put("domainCode", dataset.getDomainCode());
        canonical.put("subjectTypes", subjectTypes);
        canonical.put("queryWorkflowCode", dataset.getQueryWorkflowCode());
        canonical.put("accessWorkflowCode", dataset.getAccessWorkflowCode());
        canonical.put("inputMapping", inputMapping);
        canonical.put("ttlMinutes", dataset.getTtlMinutes());
        canonical.put("associationMode", dataset.getAssociationMode());
        canonical.put("maxConcurrency", dataset.getMaxConcurrency());
        canonical.put("enabled", dataset.getEnabled());
        return ContentHashUtils.sha256(writeJson(objectMapper.valueToTree(canonical)));
    }

    private String buildFieldPolicyChecksum(List<ReportDatasetField> fields) {
        List<ReportDatasetField> sorted = fields.stream()
                .sorted(Comparator
                        .comparing(ReportDatasetField::getFactCode)
                        .thenComparing(ReportDatasetField::getDisplayOrder))
                .toList();
        ArrayNode canonical = objectMapper.createArrayNode();
        for (ReportDatasetField field : sorted) {
            ObjectNode item = objectMapper.createObjectNode();
            item.put("factCode", field.getFactCode());
            item.put("displayOrder", field.getDisplayOrder());
            if (field.getFieldId() == null) {
                item.putNull("fieldId");
            } else {
                item.put("fieldId", field.getFieldId());
            }
            item.put("factName", field.getFactName());
            item.put("factType", field.getFactType());
            item.put("calculable", field.getCalculable());
            item.put("displayable", field.getDisplayable());
            item.put("exportable", field.getExportable());
            item.put("modelVisible", field.getModelVisible());
            item.put("filterable", field.getFilterable());
            item.put("maskStrategy", field.getMaskStrategy());
            item.put("grain", field.getGrain());
            canonical.add(item);
        }
        return ContentHashUtils.sha256(writeJson(canonical));
    }

    private void persistDataset(
            ReportDataset dataset,
            ReportDataset existing,
            String operator,
            LocalDateTime now) {
        dataset.setUpdatedBy(operator);
        dataset.setUpdatedAt(now);
        if (existing == null) {
            dataset.setId(null);
            dataset.setVersion(0);
            dataset.setCreatedBy(operator);
            dataset.setCreatedAt(now);
            try {
                if (datasetMapper.insert(dataset) != 1 || dataset.getId() == null) {
                    throw new BusinessException(500, "报告数据集当前配置新增失败");
                }
            } catch (DuplicateKeyException exception) {
                throw new BusinessException(409, "相同datasetCode的当前配置已经存在");
            }
            return;
        }

        if (dataset.getVersion() == null) {
            throw new BusinessException(400, "更新当前数据集配置时version不能为空");
        }
        dataset.setId(existing.getId());
        dataset.setCreatedBy(existing.getCreatedBy());
        dataset.setCreatedAt(existing.getCreatedAt());
        if (datasetMapper.updateById(dataset) != 1) {
            throw new BusinessException(409, "报告数据集配置已被其他人修改，请刷新后重试");
        }
    }

    private void replaceFields(
            Long datasetId,
            List<ReportDatasetField> fields,
            String operator,
            LocalDateTime now) {
        fieldMapper.delete(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .eq(ReportDatasetField::getDatasetId, datasetId)
        );
        for (ReportDatasetField field : fields) {
            field.setId(null);
            field.setDatasetId(datasetId);
            field.setCreatedBy(operator);
            field.setUpdatedBy(operator);
            field.setCreatedAt(now);
            field.setUpdatedAt(now);
            if (fieldMapper.insert(field) != 1) {
                throw new BusinessException(500, "报告数据集字段策略保存失败：" + field.getFactCode());
            }
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            Set<String> sortedNames = new TreeSet<>();
            Iterator<String> names = node.fieldNames();
            names.forEachRemaining(sortedNames::add);
            for (String name : sortedNames) {
                result.set(name, canonicalize(node.get(name)));
            }
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(item -> result.add(canonicalize(item)));
            return result;
        }
        return node.deepCopy();
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规范化配置JSON序列化失败", exception);
        }
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(400, message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
