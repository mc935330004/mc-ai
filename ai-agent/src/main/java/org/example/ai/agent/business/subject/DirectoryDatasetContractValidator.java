package org.example.ai.agent.business.subject;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 在目录执行前校验当前数据集确实是调用人范围目录，而不是误配的人员或项目详情数据集。
 */
final class DirectoryDatasetContractValidator {

    private static final Set<String> DIRECTORY_FACTS = Set.of(
            "subjectCandidates", "totalCount", "hasNext"
    );

    private final ReportDatasetMapper datasetMapper;
    private final ReportDatasetFieldMapper fieldMapper;
    private final ObjectMapper objectMapper;

    DirectoryDatasetContractValidator(
            ReportDatasetMapper datasetMapper,
            ReportDatasetFieldMapper fieldMapper,
            ObjectMapper objectMapper) {
        this.datasetMapper = Objects.requireNonNull(datasetMapper, "datasetMapper不能为空");
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
    }

    DirectoryDatasetContract validate(
            String datasetCode,
            Map<String, Object> canonicalInput) {
        ReportDataset dataset = datasetMapper.selectOne(
                Wrappers.<ReportDataset>lambdaQuery()
                        .eq(ReportDataset::getDatasetCode, datasetCode)
        );
        if (dataset == null
                || dataset.getId() == null
                || !Boolean.TRUE.equals(dataset.getEnabled())
                || !StringUtils.hasText(dataset.getConfigChecksum())
                || !StringUtils.hasText(dataset.getFieldPolicyChecksum())
                || !supportsCallerScope(dataset.getSubjectTypesJson())) {
            throw new IllegalArgumentException("主体目录数据集配置不可用");
        }
        if (!queryMappingKeys(dataset.getInputMappingJson())
                .containsAll(canonicalInput.keySet())) {
            throw new IllegalArgumentException("主体目录查询参数缺少显式映射");
        }
        validateFields(dataset.getId());
        return new DirectoryDatasetContract(
                datasetCode,
                dataset.getConfigChecksum(),
                dataset.getFieldPolicyChecksum()
        );
    }

    private boolean supportsCallerScope(String subjectTypesJson) {
        try {
            JsonNode types = objectMapper.readTree(subjectTypesJson);
            if (types == null || !types.isArray()) {
                return false;
            }
            for (JsonNode type : types) {
                if (type.isTextual()
                        && BusinessSubjectType.PERSON.name().equals(type.textValue())) {
                    return true;
                }
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private Set<String> queryMappingKeys(String inputMappingJson) {
        try {
            JsonNode root = objectMapper.readTree(inputMappingJson);
            JsonNode query = root == null ? null : root.get("query");
            if (query == null || !query.isObject()) {
                throw new IllegalArgumentException("目录数据集缺少query映射");
            }
            Set<String> keys = new LinkedHashSet<>();
            query.fields().forEachRemaining(entry -> {
                if (!StringUtils.hasText(entry.getKey())
                        || !entry.getValue().isTextual()
                        || !StringUtils.hasText(entry.getValue().textValue())
                        || !keys.add(entry.getKey())) {
                    throw new IllegalArgumentException("目录数据集query映射不合法");
                }
            });
            return Set.copyOf(keys);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("目录数据集映射不是合法JSON", exception);
        }
    }

    private void validateFields(Long datasetId) {
        List<ReportDatasetField> fields = fieldMapper.selectList(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .eq(ReportDatasetField::getDatasetId, datasetId)
        );
        if (fields == null || fields.size() != DIRECTORY_FACTS.size()) {
            throw new IllegalArgumentException("主体目录字段策略必须且只能包含目录协议字段");
        }
        Set<String> actual = new HashSet<>();
        for (ReportDatasetField field : fields) {
            if (field == null
                    || !actual.add(field.getFactCode())
                    || !Boolean.TRUE.equals(field.getDisplayable())
                    || Boolean.TRUE.equals(field.getCalculable())
                    || Boolean.TRUE.equals(field.getExportable())
                    || Boolean.TRUE.equals(field.getModelVisible())) {
                throw new IllegalArgumentException("主体目录字段策略通道不安全");
            }
        }
        if (!actual.equals(DIRECTORY_FACTS)) {
            throw new IllegalArgumentException("主体目录字段策略缺少协议字段");
        }
    }

    record DirectoryDatasetContract(
            String datasetCode,
            String configChecksum,
            String fieldPolicyChecksum) {
    }
}
