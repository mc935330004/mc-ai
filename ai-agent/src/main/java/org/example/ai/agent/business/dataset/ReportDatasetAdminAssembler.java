package org.example.ai.agent.business.dataset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.business.dataset.dto.ReportDatasetSaveDTO;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.vo.ReportDatasetDetailVO;
import org.example.ai.agent.business.dataset.vo.ReportDatasetListVO;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端结构化协议与数据集持久化模型之间的转换器。
 */
@Component
@RequiredArgsConstructor
public class ReportDatasetAdminAssembler {

    private final ObjectMapper objectMapper;

    public ReportDataset toDataset(ReportDatasetSaveDTO dto) {
        ReportDataset dataset = new ReportDataset();
        dataset.setId(dto.getId());
        dataset.setVersion(dto.getVersion());
        dataset.setDatasetCode(dto.getDatasetCode());
        dataset.setDatasetName(dto.getDatasetName());
        dataset.setDomainCode(dto.getDomainCode());
        dataset.setSubjectTypesJson(writeJson(objectMapper.valueToTree(
                dto.getSubjectTypes() == null ? List.of() : dto.getSubjectTypes()
        )));
        dataset.setQueryWorkflowCode(dto.getQueryWorkflowCode());
        dataset.setAccessWorkflowCode(dto.getAccessWorkflowCode());
        ObjectNode mappings = objectMapper.createObjectNode();
        mappings.set("query", objectMapper.valueToTree(
                dto.getQueryInputMapping() == null ? Map.of() : dto.getQueryInputMapping()
        ));
        mappings.set("access", objectMapper.valueToTree(
                dto.getAccessInputMapping() == null ? Map.of() : dto.getAccessInputMapping()
        ));
        dataset.setInputMappingJson(writeJson(mappings));
        dataset.setTtlMinutes(dto.getTtlMinutes());
        dataset.setAssociationMode(dto.getAssociationMode());
        dataset.setMaxConcurrency(dto.getMaxConcurrency());
        dataset.setEnabled(dto.getEnabled());
        return dataset;
    }

    public List<ReportDatasetField> toFields(List<ReportDatasetSaveDTO.FieldDTO> source) {
        if (source == null) {
            return List.of();
        }
        return source.stream().map(item -> {
            if (item == null) {
                return (ReportDatasetField) null;
            }
            ReportDatasetField field = new ReportDatasetField();
            field.setFieldId(item.getFieldId());
            field.setFactCode(item.getFactCode());
            field.setFactName(item.getFactName());
            field.setFactType(item.getFactType());
            field.setCalculable(item.getCalculable());
            field.setDisplayable(item.getDisplayable());
            field.setExportable(item.getExportable());
            field.setModelVisible(item.getModelVisible());
            field.setFilterable(item.getFilterable());
            field.setMaskStrategy(item.getMaskStrategy());
            field.setGrain(item.getGrain());
            field.setDisplayOrder(item.getDisplayOrder());
            return field;
        }).toList();
    }

    public ReportDatasetListVO toListView(ReportDataset dataset, int fieldCount) {
        ReportDatasetListVO view = new ReportDatasetListVO();
        view.setId(dataset.getId());
        view.setDatasetCode(dataset.getDatasetCode());
        view.setDatasetName(dataset.getDatasetName());
        view.setDomainCode(dataset.getDomainCode());
        view.setSubjectTypes(readSubjectTypes(dataset.getSubjectTypesJson()));
        view.setQueryWorkflowCode(dataset.getQueryWorkflowCode());
        view.setFieldCount(fieldCount);
        view.setEnabled(dataset.getEnabled());
        view.setVersion(dataset.getVersion());
        view.setUpdatedAt(dataset.getUpdatedAt());
        return view;
    }

    public ReportDatasetDetailVO toDetailView(
            ReportDataset dataset,
            List<ReportDatasetField> fields) {
        InputMappings mappings = readInputMappings(dataset.getInputMappingJson());
        ReportDatasetDetailVO view = new ReportDatasetDetailVO();
        view.setId(dataset.getId());
        view.setVersion(dataset.getVersion());
        view.setDatasetCode(dataset.getDatasetCode());
        view.setDatasetName(dataset.getDatasetName());
        view.setDomainCode(dataset.getDomainCode());
        view.setSubjectTypes(readSubjectTypes(dataset.getSubjectTypesJson()));
        view.setQueryWorkflowCode(dataset.getQueryWorkflowCode());
        view.setAccessWorkflowCode(dataset.getAccessWorkflowCode());
        view.setQueryInputMapping(mappings.query());
        view.setAccessInputMapping(mappings.access());
        view.setTtlMinutes(dataset.getTtlMinutes());
        view.setAssociationMode(dataset.getAssociationMode());
        view.setMaxConcurrency(dataset.getMaxConcurrency());
        view.setEnabled(dataset.getEnabled());
        view.setFields(fields.stream().map(this::toFieldDto).toList());
        view.setCreatedBy(dataset.getCreatedBy());
        view.setUpdatedBy(dataset.getUpdatedBy());
        view.setCreatedAt(dataset.getCreatedAt());
        view.setUpdatedAt(dataset.getUpdatedAt());
        return view;
    }

    public InputMappings readInputMappings(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw invalidMapping();
            }
            return new InputMappings(
                    readStringMap(root.get("query")),
                    readStringMap(root.get("access"))
            );
        } catch (JsonProcessingException exception) {
            throw invalidMapping();
        }
    }

    private ReportDatasetSaveDTO.FieldDTO toFieldDto(ReportDatasetField field) {
        ReportDatasetSaveDTO.FieldDTO dto = new ReportDatasetSaveDTO.FieldDTO();
        dto.setFieldId(field.getFieldId());
        dto.setFactCode(field.getFactCode());
        dto.setFactName(field.getFactName());
        dto.setFactType(field.getFactType());
        dto.setCalculable(field.getCalculable());
        dto.setDisplayable(field.getDisplayable());
        dto.setExportable(field.getExportable());
        dto.setModelVisible(field.getModelVisible());
        dto.setFilterable(field.getFilterable());
        dto.setMaskStrategy(field.getMaskStrategy());
        dto.setGrain(field.getGrain());
        dto.setDisplayOrder(field.getDisplayOrder());
        return dto;
    }

    private List<String> readSubjectTypes(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) {
                throw new BusinessException(500, "数据集主体类型配置不合法");
            }
            List<String> result = new ArrayList<>();
            root.forEach(item -> {
                if (!item.isTextual()) {
                    throw new BusinessException(500, "数据集主体类型配置不合法");
                }
                result.add(item.textValue());
            });
            return List.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(500, "数据集主体类型配置不合法");
        }
    }

    private Map<String, String> readStringMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalidMapping();
        }
        Map<String, String> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw invalidMapping();
            }
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        return Map.copyOf(result);
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据集管理配置序列化失败", exception);
        }
    }

    private BusinessException invalidMapping() {
        return new BusinessException(500, "数据集参数映射配置不合法");
    }

    public record InputMappings(
            Map<String, String> query,
            Map<String, String> access) {
    }
}
