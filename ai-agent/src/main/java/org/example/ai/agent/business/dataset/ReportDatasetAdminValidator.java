package org.example.ai.agent.business.dataset;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.graph.compiler.CompiledGraphNode;
import org.example.ai.agent.graph.compiler.CompiledGraphSpec;
import org.example.ai.agent.graph.compiler.GraphCapabilityCatalog;
import org.example.ai.agent.graph.config.CapabilityNodeConfig;
import org.example.ai.agent.graph.config.CompiledForEachNodeConfig;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报告数据集管理配置的工作流、参数映射和字段字典校验。
 */
@Component
@RequiredArgsConstructor
public class ReportDatasetAdminValidator {

    private final WorkflowRuntimeSnapshotResolver workflowResolver;
    private final GraphCapabilityCatalog capabilityCatalog;
    private final ReportDatasetValidator datasetValidator;
    private final FieldDictionaryMapper dictionaryMapper;
    private final ReportDatasetAdminAssembler assembler;

    public void validate(ReportDataset dataset, List<ReportDatasetField> fields) {
        PublishedWorkflow queryWorkflow = resolveReadOnly(dataset.getQueryWorkflowCode());
        ReportDatasetAdminAssembler.InputMappings mappings = assembler.readInputMappings(
                dataset.getInputMappingJson()
        );
        validateMapping(mappings.query(), queryWorkflow);

        if (StringUtils.hasText(dataset.getAccessWorkflowCode())) {
            validateMapping(mappings.access(), resolveReadOnly(dataset.getAccessWorkflowCode()));
        } else if (!mappings.access().isEmpty()) {
            throw new BusinessException(400, "未配置权限工作流时access映射必须为空");
        }
        validateDictionaryFields(fields, queryWorkflow.compiledGraph());
    }

    public PublishedWorkflow resolveReadOnly(String workflowCode) {
        PublishedWorkflow workflow = workflowResolver.resolveByCode(workflowCode);
        validateReadOnlyGraph(workflowCode, workflow == null ? null : workflow.compiledGraph());
        return workflow;
    }

    private void validateReadOnlyGraph(String workflowCode, CompiledGraphSpec graph) {
        if (graph == null || graph.nodesById() == null) {
            throw new BusinessException(409, "已发布工作流缺少可执行编译图：" + workflowCode);
        }
        for (CompiledGraphNode node : graph.nodesById().values()) {
            if (node == null) {
                continue;
            }
            if (node.config() instanceof CapabilityNodeConfig capability
                    && StringUtils.hasText(capability.capabilityCode())) {
                String code = capability.capabilityCode().trim();
                if (!"READ".equalsIgnoreCase(capabilityCatalog.sideEffect(code))) {
                    throw new BusinessException(
                            400,
                            "报告数据集只允许引用READ工作流，工作流"
                                    + workflowCode + "包含非READ能力：" + code
                    );
                }
            }
            if (node.config() instanceof CompiledForEachNodeConfig forEach) {
                validateReadOnlyGraph(workflowCode, forEach.body());
            }
        }
    }

    private void validateMapping(Map<String, String> mapping, PublishedWorkflow workflow) {
        try {
            datasetValidator.validateCanonicalMappingDefinition(mapping, workflow.inputSchema());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(400, exception.getMessage());
        }
    }

    private void validateDictionaryFields(
            List<ReportDatasetField> fields,
            CompiledGraphSpec graph) {
        Set<String> capabilityCodes = collectCapabilityCodes(graph);
        Set<Long> ids = new LinkedHashSet<>();
        fields.forEach(field -> ids.add(field.getFieldId()));
        List<FieldDictionary> dictionaries = dictionaryMapper.selectBatchIds(ids);
        Map<Long, FieldDictionary> byId = new HashMap<>();
        if (dictionaries != null) {
            dictionaries.forEach(dictionary -> {
                if (dictionary != null && dictionary.getId() != null) {
                    byId.put(dictionary.getId(), dictionary);
                }
            });
        }
        for (ReportDatasetField field : fields) {
            FieldDictionary dictionary = byId.get(field.getFieldId());
            if (dictionary == null || !"PUBLISHED".equals(dictionary.getPublishStatus())) {
                throw new BusinessException(400, "数据集引用的字段字典不存在或未发布");
            }
            if (!capabilityCodes.contains(dictionary.getCapabilityCode())) {
                throw new BusinessException(400, "字段字典不属于当前查询工作流");
            }
            validateVisibility(field, dictionary);
        }
    }

    private Set<String> collectCapabilityCodes(CompiledGraphSpec graph) {
        Set<String> result = new LinkedHashSet<>();
        for (CompiledGraphNode node : graph.nodesById().values()) {
            if (node == null) {
                continue;
            }
            if (node.config() instanceof CapabilityNodeConfig capability
                    && StringUtils.hasText(capability.capabilityCode())) {
                result.add(capability.capabilityCode().trim());
            }
            if (node.config() instanceof CompiledForEachNodeConfig forEach) {
                result.addAll(collectCapabilityCodes(forEach.body()));
            }
        }
        return result;
    }

    private void validateVisibility(
            ReportDatasetField field,
            FieldDictionary dictionary) {
        if ((Boolean.TRUE.equals(field.getDisplayable())
                || Boolean.TRUE.equals(field.getExportable()))
                && !Integer.valueOf(1).equals(dictionary.getUserVisible())) {
            throw new BusinessException(400, "数据集展示或导出策略突破字段字典边界");
        }
        if (Boolean.TRUE.equals(field.getModelVisible())
                && !Integer.valueOf(1).equals(dictionary.getModelVisible())) {
            throw new BusinessException(400, "数据集模型策略突破字段字典边界");
        }
    }
}
