package org.example.ai.agent.business.dataset.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.extractor.DictionaryFactExtractor;
import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.answer.model.UnifiedFactSet;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.CanonicalInputMapper;
import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.capability.service.FieldMetadataService;
import org.example.ai.agent.tool.FieldMeta;
import org.example.ai.agent.workflow.runtime.PublishedWorkflow;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionCommand;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionFacade;
import org.example.ai.agent.workflow.runtime.WorkflowExecutionOutcome;
import org.example.ai.agent.workflow.runtime.WorkflowRuntimeSnapshotResolver;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 报告数据集授权执行服务实现。
 *
 * 权限工作流明确允许后才解析并执行查询工作流；原始工作流响应只在本方法调用栈内使用。
 */
@Service
@RequiredArgsConstructor
public class ReportDatasetExecutionServiceImpl
        implements ReportDatasetExecutionService {

    private static final String ACCESS_SECTION = "access";
    private static final String QUERY_SECTION = "query";

    private final ReportDatasetMapper datasetMapper;
    private final ReportDatasetFieldMapper datasetFieldMapper;
    private final WorkflowRuntimeSnapshotResolver snapshotResolver;
    private final WorkflowExecutionFacade executionFacade;
    private final CanonicalInputMapper canonicalInputMapper;
    private final BusinessFactSanitizer factSanitizer;
    private final FieldDictionaryMapper fieldDictionaryMapper;
    private final FieldMetadataService fieldMetadataService;
    private final DictionaryFactExtractor factExtractor;
    private final ObjectMapper objectMapper;

    @Override
    public DatasetExecutionResult execute(
            DatasetExecutionRequest request) {
        String datasetCode = request == null
                ? null
                : request.datasetCode();

        ReportDataset dataset;
        DatasetInputMappings mappings;
        try {
            validateRequest(request);
            dataset = loadEnabledDataset(request);
            mappings = parseInputMappings(dataset.getInputMappingJson());
        } catch (RuntimeException exception) {
            return failed(
                    datasetCode,
                    "DATASET_CONFIG_INVALID",
                    "数据集配置不可用"
            );
        }

        WorkflowExecutionOutcome accessOutcome;
        try {
            PublishedWorkflow accessWorkflow = snapshotResolver.resolveByCode(
                    dataset.getAccessWorkflowCode()
            );
            Map<String, Object> accessInput = canonicalInputMapper.map(
                    selectCanonicalInput(request.canonicalInput(), mappings.access()),
                    mappings.access(),
                    accessWorkflow.inputSchema()
            );
            accessOutcome = executionFacade.execute(buildCommand(
                    request,
                    dataset.getAccessWorkflowCode(),
                    accessWorkflow.version().getId(),
                    accessInput
            ));
        } catch (RuntimeException exception) {
            return failed(
                    datasetCode,
                    "ACCESS_CHECK_FAILED",
                    "权限校验失败"
            );
        }

        AccessDecision accessDecision;
        try {
            accessDecision = parseAccessDecision(accessOutcome);
        } catch (RuntimeException exception) {
            accessDecision = AccessDecision.INVALID;
        }
        if (accessDecision == AccessDecision.INVALID) {
            return failed(
                    datasetCode,
                    "ACCESS_CHECK_FAILED",
                    "权限校验失败"
            );
        }
        if (accessDecision == AccessDecision.DENIED) {
            /*
             * 权限拒绝不能查询业务数据，也不能通过事实、数量或提示文字泄露记录是否存在。
             */
            return new DatasetExecutionResult(
                    datasetCode,
                    DatasetExecutionStatus.DENIED,
                    false,
                    Map.of(),
                    null,
                    null,
                    "ACCESS_DENIED",
                    "因权限不足未纳入"
            );
        }

        WorkflowExecutionOutcome queryOutcome;
        try {
            PublishedWorkflow queryWorkflow = snapshotResolver.resolveByCode(
                    dataset.getQueryWorkflowCode()
            );
            Map<String, Object> queryInput = canonicalInputMapper.map(
                    selectCanonicalInput(request.canonicalInput(), mappings.query()),
                    mappings.query(),
                    queryWorkflow.inputSchema()
            );
            queryOutcome = executionFacade.execute(buildCommand(
                    request,
                    dataset.getQueryWorkflowCode(),
                    queryWorkflow.version().getId(),
                    queryInput
            ));
        } catch (RuntimeException exception) {
            return failed(
                    datasetCode,
                    "QUERY_FAILED",
                    "数据查询失败"
            );
        }

        if (queryOutcome == null || !queryOutcome.success()) {
            boolean timeout = queryOutcome != null
                    && "TIMEOUT".equals(normalize(queryOutcome.errorCode()));
            return new DatasetExecutionResult(
                    datasetCode,
                    timeout
                            ? DatasetExecutionStatus.TIMEOUT
                            : DatasetExecutionStatus.FAILED,
                    false,
                    Map.of(),
                    queryOutcome == null ? null : queryOutcome.runId(),
                    null,
                    timeout ? "QUERY_TIMEOUT" : "QUERY_FAILED",
                    timeout ? "数据查询超时" : "数据查询失败"
            );
        }

        try {
            return buildSafeResult(dataset, queryOutcome);
        } catch (RuntimeException exception) {
            /*
             * 字段字典、事实映射或字段策略任一异常都必须失败关闭，不能退回原始响应。
             */
            return failed(
                    datasetCode,
                    "FACT_MAPPING_FAILED",
                    "数据处理失败",
                    queryOutcome.runId()
            );
        }
    }

    private void validateRequest(
            DatasetExecutionRequest request) {
        if (request == null
                || !StringUtils.hasText(request.agentRunId())
                || !StringUtils.hasText(request.userId())
                || !StringUtils.hasText(request.sessionId())
                || !StringUtils.hasText(request.authorization())
                || !StringUtils.hasText(request.datasetCode())
                || request.subjectType() == null
                || !StringUtils.hasText(request.subjectId())) {
            throw new IllegalArgumentException("数据集执行请求不完整");
        }
    }

    private ReportDataset loadEnabledDataset(
            DatasetExecutionRequest request) {
        ReportDataset dataset = datasetMapper.selectOne(
                Wrappers.<ReportDataset>lambdaQuery()
                        .eq(
                                ReportDataset::getDatasetCode,
                                request.datasetCode().trim()
                        )
        );
        if (dataset == null
                || !Boolean.TRUE.equals(dataset.getEnabled())
                || !StringUtils.hasText(dataset.getAccessWorkflowCode())
                || !StringUtils.hasText(dataset.getQueryWorkflowCode())
                || !supportsSubjectType(dataset, request)) {
            throw new IllegalArgumentException("数据集当前不可执行");
        }
        return dataset;
    }

    private boolean supportsSubjectType(
            ReportDataset dataset,
            DatasetExecutionRequest request) {
        try {
            JsonNode subjectTypes = objectMapper.readTree(
                    dataset.getSubjectTypesJson()
            );
            if (subjectTypes == null || !subjectTypes.isArray()) {
                return false;
            }
            for (JsonNode item : subjectTypes) {
                if (item.isTextual()
                        && request.subjectType().name().equals(item.textValue())) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private DatasetInputMappings parseInputMappings(
            String mappingJson) {
        if (!StringUtils.hasText(mappingJson)) {
            throw new IllegalArgumentException("inputMappingJson不能为空");
        }
        try {
            JsonNode root = objectMapper.readTree(mappingJson);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("inputMappingJson必须是对象");
            }
            return new DatasetInputMappings(
                    parseMappingSection(root, ACCESS_SECTION),
                    parseMappingSection(root, QUERY_SECTION)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("inputMappingJson不是合法JSON", exception);
        }
    }

    private Map<String, String> parseMappingSection(
            JsonNode root,
            String sectionName) {
        JsonNode section = root.get(sectionName);
        if (section == null || !section.isObject()) {
            throw new IllegalArgumentException("inputMappingJson缺少" + sectionName + "对象");
        }
        Map<String, String> result = new LinkedHashMap<>();
        section.fields().forEachRemaining(entry -> {
            if (!StringUtils.hasText(entry.getKey())
                    || !entry.getValue().isTextual()
                    || !StringUtils.hasText(entry.getValue().textValue())) {
                throw new IllegalArgumentException(sectionName + "映射必须是非空字符串键值");
            }
            result.put(entry.getKey(), entry.getValue().textValue());
        });
        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> selectCanonicalInput(
            Map<String, Object> canonicalInput,
            Map<String, String> mapping) {
        Map<String, Object> selected = new LinkedHashMap<>();
        for (String canonicalName : mapping.keySet()) {
            if (canonicalInput.containsKey(canonicalName)) {
                selected.put(canonicalName, canonicalInput.get(canonicalName));
            }
        }
        return selected;
    }

    private WorkflowExecutionCommand buildCommand(
            DatasetExecutionRequest request,
            String workflowCode,
            Long expectedVersionId,
            Map<String, Object> input) {
        return WorkflowExecutionCommand.builder()
                .runId(UUID.randomUUID().toString())
                .agentRunId(request.agentRunId())
                .userId(request.userId())
                .workflowCode(workflowCode)
                .expectedVersionId(expectedVersionId)
                .input(input)
                .authorization(request.authorization())
                .secureContext(request.secureContext())
                .build();
    }

    private AccessDecision parseAccessDecision(
            WorkflowExecutionOutcome outcome) {
        if (outcome == null || !outcome.success()) {
            return AccessDecision.INVALID;
        }
        Object result = outcome.result();
        if (result instanceof Boolean allowed) {
            return allowed ? AccessDecision.ALLOWED : AccessDecision.DENIED;
        }
        JsonNode root = objectMapper.valueToTree(result);
        JsonNode allowed = root == null || !root.isObject()
                ? null
                : root.get("allowed");
        if (allowed == null || !allowed.isBoolean()) {
            return AccessDecision.INVALID;
        }
        return allowed.booleanValue()
                ? AccessDecision.ALLOWED
                : AccessDecision.DENIED;
    }

    private DatasetExecutionResult buildSafeResult(
            ReportDataset dataset,
            WorkflowExecutionOutcome queryOutcome) {
        List<ReportDatasetField> datasetFields = loadDatasetFields(dataset.getId());
        DictionaryMaterial dictionaryMaterial = loadDictionaryMaterial(
                datasetFields,
                dataset.getQueryWorkflowCode()
        );

        UnifiedFactSet extracted = factExtractor.extract(
                dataset.getQueryWorkflowCode(),
                queryOutcome.result(),
                dictionaryMaterial.metas()
        );
        Map<String, Object> standardFacts = collapseStandardFacts(
                extracted,
                dictionaryMaterial.factCodes()
        );
        BusinessFactSanitizer.SanitizedFacts sanitized = factSanitizer.sanitize(
                standardFacts,
                dictionaryMaterial.policies()
        );
        Map<String, Object> envelope = safeEnvelope(sanitized);

        boolean empty = standardFacts.isEmpty();
        boolean dataComplete = !empty
                && !queryOutcome.partialSuccess()
                && extracted.dataComplete();
        return new DatasetExecutionResult(
                dataset.getDatasetCode(),
                empty
                        ? DatasetExecutionStatus.EMPTY
                        : DatasetExecutionStatus.SUCCESS,
                dataComplete,
                envelope,
                queryOutcome.runId(),
                null,
                null,
                empty ? "未查询到可用数据" : "数据查询完成"
        );
    }

    private List<ReportDatasetField> loadDatasetFields(
            Long datasetId) {
        if (datasetId == null) {
            throw new IllegalArgumentException("datasetId不能为空");
        }
        List<ReportDatasetField> fields = datasetFieldMapper.selectList(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .eq(ReportDatasetField::getDatasetId, datasetId)
                        .orderByAsc(
                                ReportDatasetField::getDisplayOrder,
                                ReportDatasetField::getId
                        )
        );
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("数据集字段策略不能为空");
        }
        return List.copyOf(fields);
    }

    private DictionaryMaterial loadDictionaryMaterial(
            List<ReportDatasetField> datasetFields,
            String queryWorkflowCode) {
        Set<Long> dictionaryIds = new LinkedHashSet<>();
        for (ReportDatasetField field : datasetFields) {
            if (field == null || field.getFieldId() == null) {
                throw new IllegalArgumentException("数据集字段缺少fieldId");
            }
            dictionaryIds.add(field.getFieldId());
        }

        List<FieldDictionary> dictionaries = fieldDictionaryMapper.selectBatchIds(dictionaryIds);
        Map<Long, FieldDictionary> dictionariesById = new LinkedHashMap<>();
        if (dictionaries != null) {
            for (FieldDictionary dictionary : dictionaries) {
                if (dictionary == null
                        || dictionary.getId() == null
                        || dictionariesById.put(dictionary.getId(), dictionary) != null) {
                    throw new IllegalArgumentException("字段字典结果无效");
                }
            }
        }

        List<FieldMeta> metas = new ArrayList<>(datasetFields.size());
        List<FieldPolicy> policies = new ArrayList<>(datasetFields.size());
        Set<String> factCodes = new LinkedHashSet<>();
        for (ReportDatasetField datasetField : datasetFields) {
            FieldDictionary dictionary = dictionariesById.get(datasetField.getFieldId());
            if (dictionary == null
                    || !"PUBLISHED".equals(dictionary.getPublishStatus())) {
                throw new IllegalArgumentException("数据集引用的字段字典未发布");
            }

            FieldMeta meta = fieldMetadataService.toFieldMeta(dictionary);
            validateVisibilityBoundary(datasetField, meta);
            if (!StringUtils.hasText(datasetField.getFactCode())
                    || !factCodes.add(datasetField.getFactCode())) {
                throw new IllegalArgumentException("数据集factCode无效");
            }
            meta.setFieldCode(datasetField.getFactCode());
            metas.add(meta);
            policies.add(toFieldPolicy(datasetField));
        }
        return new DictionaryMaterial(
                List.copyOf(metas),
                List.copyOf(policies),
                Set.copyOf(factCodes)
        );
    }

    private void validateVisibilityBoundary(
            ReportDatasetField datasetField,
            FieldMeta meta) {
        if (Boolean.TRUE.equals(datasetField.getDisplayable())
                && !Integer.valueOf(1).equals(meta.getUserVisible())) {
            throw new IllegalArgumentException("数据集展示策略突破字段字典边界");
        }
        if (Boolean.TRUE.equals(datasetField.getModelVisible())
                && !Integer.valueOf(1).equals(meta.getModelVisible())) {
            throw new IllegalArgumentException("数据集模型策略突破字段字典边界");
        }
    }

    private FieldPolicy toFieldPolicy(
            ReportDatasetField field) {
        return new FieldPolicy(
                field.getFactCode(),
                field.getFactType(),
                Boolean.TRUE.equals(field.getCalculable()),
                Boolean.TRUE.equals(field.getDisplayable()),
                Boolean.TRUE.equals(field.getExportable()),
                Boolean.TRUE.equals(field.getModelVisible()),
                field.getMaskStrategy(),
                field.getGrain()
        );
    }

    private Map<String, Object> collapseStandardFacts(
            UnifiedFactSet extracted,
            Set<String> allowedFactCodes) {
        Map<String, List<Object>> grouped = new LinkedHashMap<>();
        if (extracted != null) {
            for (AnswerFact fact : extracted.facts()) {
                if (fact == null
                        || fact.isMissing()
                        || fact.getRawValue() == null
                        || !allowedFactCodes.contains(fact.getFieldCode())) {
                    continue;
                }
                grouped.computeIfAbsent(
                        fact.getFieldCode(),
                        ignored -> new ArrayList<>()
                ).add(fact.getRawValue());
            }
        }

        Map<String, Object> standardFacts = new LinkedHashMap<>();
        for (Map.Entry<String, List<Object>> entry : grouped.entrySet()) {
            List<Object> values = entry.getValue();
            standardFacts.put(
                    entry.getKey(),
                    values.size() == 1
                            ? values.get(0)
                            : Collections.unmodifiableList(new ArrayList<>(values))
            );
        }
        return standardFacts;
    }

    private Map<String, Object> safeEnvelope(
            BusinessFactSanitizer.SanitizedFacts sanitized) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("calculation", sanitized.calculationFacts());
        envelope.put("display", sanitized.displayFacts());
        envelope.put("export", sanitized.exportFacts());
        envelope.put("model", sanitized.modelFacts());
        return Collections.unmodifiableMap(envelope);
    }

    private DatasetExecutionResult failed(
            String datasetCode,
            String errorCode,
            String message) {
        return failed(datasetCode, errorCode, message, null);
    }

    private DatasetExecutionResult failed(
            String datasetCode,
            String errorCode,
            String message,
            String workflowRunId) {
        return new DatasetExecutionResult(
                datasetCode,
                DatasetExecutionStatus.FAILED,
                false,
                Map.of(),
                workflowRunId,
                null,
                errorCode,
                message
        );
    }

    private String normalize(
            String value) {
        return StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : "";
    }

    private enum AccessDecision {
        ALLOWED,
        DENIED,
        INVALID
    }

    private record DatasetInputMappings(
            Map<String, String> access,
            Map<String, String> query) {
    }

    private record DictionaryMaterial(
            List<FieldMeta> metas,
            List<FieldPolicy> policies,
            Set<String> factCodes) {
    }
}
