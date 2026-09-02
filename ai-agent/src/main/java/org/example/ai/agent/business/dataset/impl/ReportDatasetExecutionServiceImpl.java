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
import org.example.ai.agent.business.dataset.DatasetAccessWorkflowExecutor;
import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.capability.service.FieldMetadataService;
import org.example.ai.agent.graph.compiler.GraphCapabilityCatalog;
import org.example.ai.agent.tool.FieldMeta;
import org.example.ai.agent.workflow.answer.WorkflowCapabilityCodeCollector;
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
    private final WorkflowCapabilityCodeCollector capabilityCodeCollector;
    private final GraphCapabilityCatalog capabilityCatalog;
    private final DatasetExecutionProofService proofService;
    private final ObjectMapper objectMapper;
    private final DatasetAccessWorkflowExecutor accessWorkflowExecutor;

    @Override
    public DatasetExecutionResult execute(
            DatasetExecutionRequest request) {
        ReportDataset dataset;
        DatasetInputMappings mappings;
        DatasetExecutionSource executionSource;
        try {
            validateRequest(request);
            dataset = loadEnabledDataset(request);
            mappings = parseInputMappings(dataset.getInputMappingJson());
            executionSource = executionSource(request, dataset, null);
        } catch (RuntimeException exception) {
            /*
             * 数据集不存在或校验和非法时无法形成可信执行来源，禁止制造占位来源继续流转。
             */
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "数据集配置不可用",
                    exception
            );
        }

        DatasetAccessWorkflowExecutor.AccessDecision accessDecision =
                accessWorkflowExecutor.authorize(
                        new DatasetAccessWorkflowExecutor.AccessRequest(
                                request.agentRunId(), request.userId(), request.authorization(),
                                request.secureContext(), request.canonicalInput()
                        ),
                        dataset
                );
        if (accessDecision == DatasetAccessWorkflowExecutor.AccessDecision.FAILED) {
            return failed(
                    executionSource,
                    "ACCESS_CHECK_FAILED",
                    "权限校验失败"
            );
        }
        if (accessDecision == DatasetAccessWorkflowExecutor.AccessDecision.DENIED) {
            /*
             * 权限拒绝不能查询业务数据，也不能通过事实、数量或提示文字泄露记录是否存在。
             */
            return signed(
                    executionSource,
                    DatasetExecutionStatus.DENIED,
                    false,
                    Map.of(),
                    null,
                    null,
                    "ACCESS_DENIED",
                    "因权限不足未纳入"
            );
        }

        Set<String> queryReadCapabilities;
        WorkflowExecutionOutcome queryOutcome;
        DatasetExecutionSource querySource = executionSource;
        try {
            PublishedWorkflow queryWorkflow = snapshotResolver.resolveByCode(
                    dataset.getQueryWorkflowCode()
            );
            querySource = executionSource(
                    request,
                    dataset,
                    queryWorkflow.version().getId()
            );
            queryReadCapabilities = resolveReadCapabilities(queryWorkflow);
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
                    querySource,
                    "QUERY_FAILED",
                    "数据查询失败"
            );
        }

        if (queryOutcome == null || !queryOutcome.success()) {
            boolean timeout = queryOutcome != null
                    && "TIMEOUT".equals(normalize(queryOutcome.errorCode()));
            return signed(
                    querySource,
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
            return buildSafeResult(
                    dataset,
                    queryReadCapabilities,
                    queryOutcome,
                    querySource
            );
        } catch (RuntimeException exception) {
            /*
             * 字段字典、事实映射或字段策略任一异常都必须失败关闭，不能退回原始响应。
             */
            return failed(
                    querySource,
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

    private DatasetExecutionResult buildSafeResult(
            ReportDataset dataset,
            Set<String> readCapabilities,
            WorkflowExecutionOutcome queryOutcome,
            DatasetExecutionSource executionSource) {
        List<ReportDatasetField> datasetFields = loadDatasetFields(dataset.getId());
        DictionaryMaterial dictionaryMaterial = loadDictionaryMaterial(
                datasetFields,
                readCapabilities
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
        return signed(
                executionSource,
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
            Set<String> readCapabilities) {
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
            validateDictionaryCapability(dictionary, readCapabilities);

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
        if (Boolean.TRUE.equals(datasetField.getExportable())
                && !Integer.valueOf(1).equals(meta.getUserVisible())) {
            throw new IllegalArgumentException("数据集导出策略突破字段字典边界");
        }
        if (Boolean.TRUE.equals(datasetField.getModelVisible())
                && !Integer.valueOf(1).equals(meta.getModelVisible())) {
            throw new IllegalArgumentException("数据集模型策略突破字段字典边界");
        }
    }

    /**
     * 使用统一收集器覆盖 FOREACH 等嵌套子图，并在事实提取前重新确认所有来源能力仍为 READ。
     */
    private Set<String> resolveReadCapabilities(
            PublishedWorkflow queryWorkflow) {
        if (queryWorkflow == null || queryWorkflow.compiledGraph() == null) {
            throw new IllegalArgumentException("查询工作流缺少编译图");
        }
        List<String> collected = capabilityCodeCollector.collect(
                queryWorkflow.compiledGraph()
        );
        if (collected.isEmpty()) {
            throw new IllegalArgumentException("查询工作流没有可用READ能力");
        }

        Set<String> readCapabilities = new LinkedHashSet<>();
        for (String capabilityCode : collected) {
            if (!StringUtils.hasText(capabilityCode)) {
                throw new IllegalArgumentException("查询工作流包含非READ能力");
            }
            String normalized = capabilityCode.trim();
            if (!"READ".equalsIgnoreCase(
                    capabilityCatalog.sideEffect(normalized)
            )) {
                throw new IllegalArgumentException("查询工作流包含非READ能力");
            }
            readCapabilities.add(normalized);
        }
        return Collections.unmodifiableSet(readCapabilities);
    }

    /**
     * 字段路径只能来自当前查询工作流实际调用的已确认 READ 能力，禁止跨能力借用字典。
     */
    private void validateDictionaryCapability(
            FieldDictionary dictionary,
            Set<String> readCapabilities) {
        String capabilityCode = dictionary.getCapabilityCode();
        if (!StringUtils.hasText(capabilityCode)) {
            throw new IllegalArgumentException("字段字典缺少capabilityCode");
        }
        String normalized = capabilityCode.trim();
        if (!readCapabilities.contains(normalized)) {
            throw new IllegalArgumentException("字段字典不属于当前查询工作流READ能力");
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
            DatasetExecutionSource source,
            String errorCode,
            String message) {
        return failed(source, errorCode, message, null);
    }

    private DatasetExecutionResult failed(
            DatasetExecutionSource source,
            String errorCode,
            String message,
            String workflowRunId) {
        return signed(
                source,
                DatasetExecutionStatus.FAILED,
                false,
                Map.of(),
                workflowRunId,
                null,
                errorCode,
                message
        );
    }

    private DatasetExecutionResult signed(
            DatasetExecutionSource source,
            DatasetExecutionStatus status,
            boolean dataComplete,
            Map<String, Object> safeFacts,
            String workflowRunId,
            String resultArtifactId,
            String safeErrorCode,
            String safeMessage) {
        DatasetExecutionResult unsigned = new DatasetExecutionResult(
                source,
                status,
                dataComplete,
                safeFacts,
                workflowRunId,
                resultArtifactId,
                safeErrorCode,
                safeMessage,
                null
        );
        return proofService.sign(unsigned);
    }

    private DatasetExecutionSource executionSource(
            DatasetExecutionRequest request,
            ReportDataset dataset,
            Long queryWorkflowVersionId) {
        String canonicalInputHash = ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(request.canonicalInput())
        );
        return new DatasetExecutionSource(
                request.userId(),
                request.sessionId(),
                request.subjectType(),
                request.subjectId(),
                dataset.getDatasetCode(),
                canonicalInputHash,
                dataset.getQueryWorkflowCode(),
                queryWorkflowVersionId,
                dataset.getConfigChecksum(),
                dataset.getFieldPolicyChecksum()
        );
    }

    private String normalize(
            String value) {
        return StringUtils.hasText(value)
                ? value.trim().toUpperCase(Locale.ROOT)
                : "";
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
