package org.example.ai.agent.business.dataset.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.business.dataset.ReportDatasetService;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

        dataset.setConfigChecksum(buildConfigChecksum(dataset, subjectTypes, inputMapping));
        dataset.setFieldPolicyChecksum(buildFieldPolicyChecksum(validatedFields));

        ReportDataset existing = datasetMapper.selectOne(
                Wrappers.<ReportDataset>lambdaQuery()
                        .eq(ReportDataset::getDatasetCode, dataset.getDatasetCode())
        );
        LocalDateTime now = LocalDateTime.now();
        persistDataset(dataset, existing, operator, now);
        replaceFields(dataset.getId(), validatedFields, operator, now);
        return dataset;
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
