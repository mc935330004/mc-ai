package org.example.ai.agent.business.snapshot.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.DatasetExecutionProofVerifier;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.snapshot.BusinessSnapshotService;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshotItem;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotItemMapper;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.workflow.answer.artifact.entity.ResultArtifact;
import org.example.ai.agent.workflow.answer.artifact.mapper.ResultArtifactMapper;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.example.ai.agent.workflow.run.mapper.WorkflowRunMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 安全业务快照持久化实现。
 */
@Service
public class BusinessSnapshotServiceImpl implements BusinessSnapshotService {

    private static final int GLOBAL_MAX_TTL_MINUTES = 24 * 60;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final Set<String> FACT_CHANNELS = Set.of(
            "calculation", "display", "export", "model"
    );
    private static final Set<String> MASK_STRATEGIES = Set.of(
            "NONE", "PARTIAL", "HASH", "SUMMARY_ONLY"
    );

    private final BusinessSnapshotMapper snapshotMapper;
    private final BusinessSnapshotItemMapper itemMapper;
    private final ResultArtifactMapper artifactMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final ReportDatasetMapper datasetMapper;
    private final ReportDatasetFieldMapper datasetFieldMapper;
    private final DatasetExecutionProofVerifier proofVerifier;
    private final BusinessFactSanitizer factSanitizer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxFactBytes;
    private final int maxQueryBytes;
    private final int maxItems;

    @Autowired
    public BusinessSnapshotServiceImpl(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ResultArtifactMapper artifactMapper,
            WorkflowRunMapper workflowRunMapper,
            ReportDatasetMapper datasetMapper,
            ReportDatasetFieldMapper datasetFieldMapper,
            DatasetExecutionProofVerifier proofVerifier,
            BusinessFactSanitizer factSanitizer,
            ObjectMapper objectMapper,
            @Value("${ai.business.snapshot.max-fact-bytes:262144}") int maxFactBytes,
            @Value("${ai.business.snapshot.max-query-bytes:65536}") int maxQueryBytes,
            @Value("${ai.business.snapshot.max-items:1000}") int maxItems) {
        this(
                snapshotMapper, itemMapper, artifactMapper, workflowRunMapper,
                datasetMapper, datasetFieldMapper, proofVerifier, factSanitizer, objectMapper,
                Clock.systemDefaultZone(), maxFactBytes, maxQueryBytes, maxItems
        );
    }

    /**
     * 测试构造器允许固定时钟和容量边界，生产配置仍由上方构造器注入。
     */
    public BusinessSnapshotServiceImpl(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ResultArtifactMapper artifactMapper,
            WorkflowRunMapper workflowRunMapper,
            ReportDatasetMapper datasetMapper,
            ReportDatasetFieldMapper datasetFieldMapper,
            DatasetExecutionProofVerifier proofVerifier,
            BusinessFactSanitizer factSanitizer,
            ObjectMapper objectMapper,
            Clock clock,
            int maxFactBytes,
            int maxQueryBytes,
            int maxItems) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper不能为空");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper不能为空");
        this.artifactMapper = Objects.requireNonNull(artifactMapper, "artifactMapper不能为空");
        this.workflowRunMapper = Objects.requireNonNull(workflowRunMapper, "workflowRunMapper不能为空");
        this.datasetMapper = Objects.requireNonNull(datasetMapper, "datasetMapper不能为空");
        this.datasetFieldMapper = Objects.requireNonNull(datasetFieldMapper, "datasetFieldMapper不能为空");
        this.proofVerifier = Objects.requireNonNull(proofVerifier, "proofVerifier不能为空");
        this.factSanitizer = Objects.requireNonNull(factSanitizer, "factSanitizer不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
        if (maxFactBytes <= 0 || maxQueryBytes <= 0 || maxItems <= 0) {
            throw new IllegalArgumentException("快照容量限制必须大于0");
        }
        this.maxFactBytes = maxFactBytes;
        this.maxQueryBytes = maxQueryBytes;
        this.maxItems = maxItems;
    }

    /**
     * 当前配置重读、来源校验和快照写入必须处于同一事务，避免配置或来源快照并发漂移。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public BusinessSnapshot create(CreateCommand command) {
        ValidatedCommand validated = validateCommand(command);
        DatasetContext dataset = loadCurrentDataset(validated.datasetCode());
        LocalDateTime now = LocalDateTime.now(clock);
        BusinessSnapshot sourceSnapshot = lockAndValidateSource(
                validated,
                dataset,
                now
        );
        List<VerifiedItem> items = verifyItems(validated, dataset, now);
        if (items.stream().noneMatch(item -> item.run() != null)) {
            /* 无运行的失败项可参与快照，但至少需一个已校验运行锁定实际工作流版本。 */
            throw badRequest("快照缺少可验证的工作流运行引用");
        }

        LocalDateTime expiresAt = calculateExpiry(dataset.ttlMinutes(), sourceSnapshot, items, now);
        BusinessSnapshot snapshot = buildSnapshot(validated, dataset, items, expiresAt, now);
        List<BusinessSnapshotItem> entities = items.stream()
                .map(item -> toEntity(snapshot.getSnapshotId(), item, now))
                .toList();

        if (snapshotMapper.insert(snapshot) != 1) {
            throw internal("业务快照写入失败");
        }
        for (BusinessSnapshotItem entity : entities) {
            if (itemMapper.insert(entity) != 1) {
                throw internal("业务快照执行项写入失败");
            }
        }
        return snapshot;
    }

    private ValidatedCommand validateCommand(CreateCommand command) {
        if (command == null || command.subjectType() == null) {
            throw badRequest("快照创建命令不完整");
        }
        String userId = requireText(command.userId(), 128, "userId");
        String sessionId = requireText(command.sessionId(), 64, "sessionId");
        String subjectId = requireText(command.subjectId(), 128, "subjectId");
        String datasetCode = requireText(command.datasetCode(), 128, "datasetCode");
        String sourceSnapshotId = optionalText(command.sourceSnapshotId(), 32, "sourceSnapshotId");
        if (command.items().isEmpty()) {
            throw badRequest("快照执行项不能为空");
        }
        if (command.items().size() > maxItems) {
            throw badRequest("快照执行项不能超过" + maxItems + "个");
        }
        String queryHash = ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(command.canonicalQuery())
        );
        return new ValidatedCommand(
                userId,
                sessionId,
                command.subjectType().name(),
                subjectId,
                datasetCode,
                command.canonicalQuery(),
                queryHash,
                sourceSnapshotId,
                command.items()
        );
    }

    private DatasetContext loadCurrentDataset(String datasetCode) {
        ReportDataset dataset = datasetMapper.selectOne(
                Wrappers.<ReportDataset>lambdaQuery()
                        .eq(ReportDataset::getDatasetCode, datasetCode)
                        .eq(ReportDataset::getEnabled, true)
                        .last("LIMIT 1 FOR UPDATE")
        );
        if (dataset == null
                || dataset.getId() == null
                || !Boolean.TRUE.equals(dataset.getEnabled())
                || !Objects.equals(dataset.getDatasetCode(), datasetCode)) {
            throw badRequest("数据集当前不可用，请重新查询");
        }
        String workflowCode = requireText(
                dataset.getQueryWorkflowCode(), 128, "queryWorkflowCode"
        );
        String configChecksum = requireChecksum(dataset.getConfigChecksum(), "数据集配置校验和");
        String policyChecksum = requireChecksum(dataset.getFieldPolicyChecksum(), "字段策略校验和");
        Integer ttlMinutes = dataset.getTtlMinutes();
        if (ttlMinutes == null || ttlMinutes < 1 || ttlMinutes > GLOBAL_MAX_TTL_MINUTES) {
            throw badRequest("数据集TTL必须在1至1440分钟之间");
        }

        List<ReportDatasetField> fields = datasetFieldMapper.selectList(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .eq(ReportDatasetField::getDatasetId, dataset.getId())
                        .orderByAsc(ReportDatasetField::getDisplayOrder, ReportDatasetField::getId)
        );
        if (fields == null || fields.isEmpty()) {
            throw badRequest("数据集字段策略不可用，请重新查询");
        }
        FieldPolicyContext fieldPolicy = buildFieldPolicyContext(fields);
        return new DatasetContext(
                datasetCode,
                workflowCode,
                configChecksum,
                policyChecksum,
                ttlMinutes,
                fieldPolicy.channels(),
                fieldPolicy.policies()
        );
    }

    private FieldPolicyContext buildFieldPolicyContext(List<ReportDatasetField> fields) {
        Set<String> allFacts = new HashSet<>();
        Set<String> calculation = new LinkedHashSet<>();
        Set<String> display = new LinkedHashSet<>();
        Set<String> export = new LinkedHashSet<>();
        Set<String> model = new LinkedHashSet<>();
        List<FieldPolicy> policies = new ArrayList<>(fields.size());
        for (ReportDatasetField field : fields) {
            if (field == null) {
                throw badRequest("数据集字段策略包含空项");
            }
            String factCode = requireText(field.getFactCode(), 128, "factCode");
            if (!allFacts.add(factCode)) {
                throw badRequest("数据集字段策略factCode重复");
            }
            if (field.getCalculable() == null
                    || field.getDisplayable() == null
                    || field.getExportable() == null
                    || field.getModelVisible() == null) {
                throw badRequest("数据集字段策略布尔值不能为空");
            }
            String factType = requireText(field.getFactType(), 32, "factType");
            String maskStrategy = requireText(field.getMaskStrategy(), 32, "maskStrategy");
            String grain = requireText(field.getGrain(), 64, "grain");
            if (!MASK_STRATEGIES.contains(maskStrategy)) {
                throw badRequest("不支持的maskStrategy：" + maskStrategy);
            }
            addWhen(calculation, factCode, field.getCalculable());
            addWhen(display, factCode, field.getDisplayable());
            addWhen(export, factCode, field.getExportable());
            addWhen(model, factCode, field.getModelVisible());
            policies.add(new FieldPolicy(
                    factCode,
                    factType,
                    field.getCalculable(),
                    field.getDisplayable(),
                    field.getExportable(),
                    field.getModelVisible(),
                    maskStrategy,
                    grain
            ));
        }
        return new FieldPolicyContext(
                new FactChannels(
                        Set.copyOf(calculation),
                        Set.copyOf(display),
                        Set.copyOf(export),
                        Set.copyOf(model)
                ),
                List.copyOf(policies)
        );
    }

    private void addWhen(Set<String> target, String factCode, Boolean enabled) {
        if (Boolean.TRUE.equals(enabled)) {
            target.add(factCode);
        }
    }

    private BusinessSnapshot lockAndValidateSource(
            ValidatedCommand command,
            DatasetContext dataset,
            LocalDateTime now) {
        if (command.sourceSnapshotId() == null) {
            return null;
        }
        BusinessSnapshot source = snapshotMapper.selectOne(
                Wrappers.<BusinessSnapshot>lambdaQuery()
                        .eq(BusinessSnapshot::getSnapshotId, command.sourceSnapshotId())
                        .eq(BusinessSnapshot::getUserId, command.userId())
                        .eq(BusinessSnapshot::getSessionId, command.sessionId())
                        .last("LIMIT 1 FOR UPDATE")
        );
        boolean usable = source != null
                && Objects.equals(source.getUserId(), command.userId())
                && Objects.equals(source.getSessionId(), command.sessionId())
                && Objects.equals(source.getSubjectType(), command.subjectType())
                && Objects.equals(source.getSubjectId(), command.subjectId())
                && Objects.equals(source.getDatasetCode(), command.datasetCode())
                && Objects.equals(source.getConfigChecksum(), dataset.configChecksum())
                && Objects.equals(source.getFieldPolicyChecksum(), dataset.fieldPolicyChecksum())
                && Set.of("COMPLETE", "PARTIAL_SUCCESS").contains(source.getStatus())
                && source.getExpiresAt() != null
                && source.getExpiresAt().isAfter(now);
        /* 不区分不存在和归属、配置不匹配，避免泄露其他用户快照。 */
        if (!usable) {
            throw badRequest("来源快照不可用，请重新查询业务数据");
        }
        return source;
    }

    private List<VerifiedItem> verifyItems(
            ValidatedCommand command,
            DatasetContext dataset,
            LocalDateTime now) {
        Set<String> itemKeys = new HashSet<>();
        List<VerifiedItem> verified = new ArrayList<>(command.items().size());
        for (ItemCommand item : command.items()) {
            if (item == null || item.result() == null) {
                throw badRequest("快照执行项及结果不能为空");
            }
            /* 证明只确认同进程 Task6 执行来源，后续权限和当前配置校验仍不可省略。 */
            if (!proofVerifier.verify(item.result())) {
                throw badRequest("数据集执行结果完整性证明无效，请重新查询");
            }
            String itemKey = requireText(item.itemKey(), 128, "itemKey");
            if (!itemKeys.add(itemKey)) {
                throw badRequest("itemKey重复");
            }
            verifySource(item.result().source(), command, dataset);
            DatasetExecutionStatus status = requireTerminalStatus(item.result().status());
            verifySafeFacts(item.result(), status, dataset);
            WorkflowRun run = verifyWorkflowRun(item.result(), status, dataset);
            ResultArtifact artifact = verifyArtifact(item.result(), status, command, run, now);
            verified.add(new VerifiedItem(item, itemKey, persistedStatus(status), run, artifact));
        }
        return List.copyOf(verified);
    }

    private void verifySource(
            DatasetExecutionSource source,
            ValidatedCommand command,
            DatasetContext dataset) {
        boolean sameSource = source != null
                && Objects.equals(source.userId(), command.userId())
                && Objects.equals(source.sessionId(), command.sessionId())
                && Objects.equals(source.subjectType().name(), command.subjectType())
                && Objects.equals(source.subjectId(), command.subjectId())
                && Objects.equals(source.datasetCode(), command.datasetCode())
                && Objects.equals(source.canonicalInputHash(), command.queryHash())
                && Objects.equals(source.queryWorkflowCode(), dataset.workflowCode())
                && Objects.equals(source.datasetConfigChecksum(), dataset.configChecksum())
                && Objects.equals(source.fieldPolicyChecksum(), dataset.fieldPolicyChecksum());
        if (!sameSource) {
            throw badRequest("执行来源与当前查询或配置不一致，请重新查询");
        }
    }

    private DatasetExecutionStatus requireTerminalStatus(DatasetExecutionStatus status) {
        if (status == null || !Set.of(
                DatasetExecutionStatus.SUCCESS,
                DatasetExecutionStatus.EMPTY,
                DatasetExecutionStatus.FAILED,
                DatasetExecutionStatus.TIMEOUT
        ).contains(status)) {
            throw badRequest("数据集执行项尚未进入可持久化终态");
        }
        return status;
    }

    private void verifySafeFacts(
            DatasetExecutionResult result,
            DatasetExecutionStatus status,
            DatasetContext dataset) {
        if (status == DatasetExecutionStatus.FAILED || status == DatasetExecutionStatus.TIMEOUT) {
            if (!result.safeFacts().isEmpty()) {
                throw badRequest("失败或超时执行项不能携带业务事实");
            }
            return;
        }
        if (!result.safeFacts().keySet().equals(FACT_CHANNELS)) {
            throw badRequest("安全事实通道与字段策略不一致");
        }
        FactChannels expected = dataset.factChannels();
        Map<String, Object> calculation = verifyChannel(
                result.safeFacts().get("calculation"), expected.calculation()
        );
        Map<String, Object> display = verifyChannel(
                result.safeFacts().get("display"), expected.display()
        );
        Map<String, Object> export = verifyChannel(
                result.safeFacts().get("export"), expected.export()
        );
        Map<String, Object> model = verifyChannel(
                result.safeFacts().get("model"), expected.model()
        );
        verifyCalculablePolicies(dataset.fieldPolicies(), calculation, display, export, model);
        verifyVisibleChannelConsistency(dataset.fieldPolicies(), display, export, model);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verifyChannel(Object value, Set<String> expectedFacts) {
        if (!(value instanceof Map<?, ?> channel)
                || !channel.keySet().equals(expectedFacts)) {
            throw badRequest("安全事实字段与当前字段策略不一致");
        }
        return (Map<String, Object>) channel;
    }

    private void verifyCalculablePolicies(
            List<FieldPolicy> policies,
            Map<String, Object> calculation,
            Map<String, Object> display,
            Map<String, Object> export,
            Map<String, Object> model) {
        List<FieldPolicy> calculablePolicies = policies.stream()
                .filter(FieldPolicy::calculable)
                .toList();
        if (calculablePolicies.isEmpty()) {
            return;
        }
        Map<String, Object> standardFacts = new LinkedHashMap<>();
        for (FieldPolicy policy : calculablePolicies) {
            Object value = calculation.get(policy.factCode());
            if (value != BusinessFactSanitizer.MissingValue.INSTANCE) {
                standardFacts.put(policy.factCode(), value);
            }
        }
        BusinessFactSanitizer.SanitizedFacts expected = factSanitizer.sanitize(
                standardFacts,
                calculablePolicies
        );
        for (FieldPolicy policy : calculablePolicies) {
            if (!Objects.equals(
                    calculation.get(policy.factCode()),
                    expected.calculationFacts().get(policy.factCode())
            )) {
                throw badRequest("安全事实计算值与当前字段策略不一致");
            }
            verifyVisibleValue(policy, display, expected.displayFacts());
            verifyVisibleValue(policy, export, expected.exportFacts());
            verifyVisibleValue(policy, model, expected.modelFacts());
        }
    }

    private void verifyVisibleValue(
            FieldPolicy policy,
            Map<String, Object> actual,
            Map<String, Object> expected) {
        if (expected.containsKey(policy.factCode())
                && !Objects.equals(
                actual.get(policy.factCode()),
                expected.get(policy.factCode())
        )) {
            throw badRequest("安全事实脱敏值与当前字段策略不一致");
        }
    }

    private void verifyVisibleChannelConsistency(
            List<FieldPolicy> policies,
            Map<String, Object> display,
            Map<String, Object> export,
            Map<String, Object> model) {
        for (FieldPolicy policy : policies) {
            if (!(policy.displayable() || policy.exportable() || policy.modelVisible())) {
                continue;
            }
            consistentVisibleValue(policy, display, export, model);
        }
    }

    private Object consistentVisibleValue(
            FieldPolicy policy,
            Map<String, Object> display,
            Map<String, Object> export,
            Map<String, Object> model) {
        List<Object> values = new ArrayList<>(3);
        if (policy.displayable()) {
            values.add(display.get(policy.factCode()));
        }
        if (policy.exportable()) {
            values.add(export.get(policy.factCode()));
        }
        if (policy.modelVisible()) {
            values.add(model.get(policy.factCode()));
        }
        Object first = values.get(0);
        if (values.stream().skip(1).anyMatch(value -> !Objects.equals(first, value))) {
            throw badRequest("同一字段的可见通道值不一致");
        }
        return first;
    }

    private WorkflowRun verifyWorkflowRun(
            DatasetExecutionResult result,
            DatasetExecutionStatus status,
            DatasetContext dataset) {
        if (!StringUtils.hasText(result.workflowRunId())) {
            if (isSuccessful(status)) {
                throw badRequest("成功或无数据执行项缺少工作流运行引用");
            }
            return null;
        }
        requireText(result.workflowRunId(), 64, "workflowRunId");
        WorkflowRun run = workflowRunMapper.selectOne(
                Wrappers.<WorkflowRun>lambdaQuery()
                        .eq(WorkflowRun::getRunId, result.workflowRunId())
                        .eq(WorkflowRun::getUserId, result.source().userId())
                        .eq(WorkflowRun::getWorkflowCode, dataset.workflowCode())
                        .last("LIMIT 1")
        );
        if (run == null
                || !Objects.equals(run.getUserId(), result.source().userId())
                || !Objects.equals(run.getWorkflowCode(), dataset.workflowCode())
                || !Objects.equals(run.getWorkflowVersionId(), result.source().queryWorkflowVersionId())) {
            throw badRequest("工作流运行引用与执行来源不一致");
        }
        requireText(run.getWorkflowCode(), 128, "workflowCode");
        requireChecksum(run.getConfigChecksum(), "工作流配置校验和");
        boolean successfulRun = Set.of("SUCCESS", "PARTIAL_SUCCESS").contains(run.getStatus());
        if (isSuccessful(status) && !successfulRun) {
            throw badRequest("工作流运行状态与成功结果不一致");
        }
        /* 字段映射失败发生在查询运行成功之后，只对该错误允许成功运行引用。 */
        boolean factMappingFailure = status == DatasetExecutionStatus.FAILED
                && "FACT_MAPPING_FAILED".equals(result.safeErrorCode());
        if (factMappingFailure && !successfulRun) {
            throw badRequest("工作流运行状态与字段映射失败不一致");
        }
        if (!isSuccessful(status)
                && !factMappingFailure
                && !"FAILED".equals(run.getStatus())) {
            throw badRequest("工作流运行状态与失败结果不一致");
        }
        if (status == DatasetExecutionStatus.TIMEOUT) {
            String errorCode = StringUtils.hasText(run.getErrorCode())
                    ? run.getErrorCode().trim().toUpperCase(Locale.ROOT)
                    : null;
            if (errorCode == null || !errorCode.contains("TIMEOUT")) {
                throw badRequest("工作流运行状态与超时结果不一致");
            }
        }
        return run;
    }

    private ResultArtifact verifyArtifact(
            DatasetExecutionResult result,
            DatasetExecutionStatus status,
            ValidatedCommand command,
            WorkflowRun run,
            LocalDateTime now) {
        if (!StringUtils.hasText(result.resultArtifactId())) {
            return null;
        }
        if (!isSuccessful(status)) {
            throw badRequest("失败或超时执行项不能引用结果制品");
        }
        requireText(result.resultArtifactId(), 32, "resultArtifactId");
        if (run == null) {
            throw badRequest("结果制品缺少对应工作流运行引用");
        }
        ResultArtifact artifact = artifactMapper.selectOne(
                Wrappers.<ResultArtifact>lambdaQuery()
                        .eq(ResultArtifact::getId, result.resultArtifactId())
                        .eq(ResultArtifact::getUserId, command.userId())
                        .eq(ResultArtifact::getSessionId, command.sessionId())
                        .last("LIMIT 1")
        );
        boolean sameExecution = artifact != null
                && Objects.equals(artifact.getUserId(), command.userId())
                && Objects.equals(artifact.getSessionId(), command.sessionId())
                && "COMPLETE".equals(artifact.getStatus())
                && Objects.equals(artifact.getRunId(), run.getRunId())
                && Objects.equals(artifact.getWorkflowCode(), run.getWorkflowCode())
                && Objects.equals(artifact.getWorkflowVersionId(), run.getWorkflowVersionId());
        if (!sameExecution) {
            throw badRequest("结果制品不可用，请重新查询业务数据");
        }
        if (artifact.getExpiresAt() == null || !artifact.getExpiresAt().isAfter(now)) {
            throw badRequest("结果制品已过期，请重新查询业务数据");
        }
        return artifact;
    }

    private LocalDateTime calculateExpiry(
            int ttlMinutes,
            BusinessSnapshot sourceSnapshot,
            List<VerifiedItem> items,
            LocalDateTime now) {
        LocalDateTime expiresAt = now.plusMinutes(Math.min(ttlMinutes, GLOBAL_MAX_TTL_MINUTES));
        if (sourceSnapshot != null && sourceSnapshot.getExpiresAt().isBefore(expiresAt)) {
            expiresAt = sourceSnapshot.getExpiresAt();
        }
        for (VerifiedItem item : items) {
            if (item.artifact() != null && item.artifact().getExpiresAt().isBefore(expiresAt)) {
                expiresAt = item.artifact().getExpiresAt();
            }
        }
        return expiresAt;
    }

    private BusinessSnapshot buildSnapshot(
            ValidatedCommand command,
            DatasetContext dataset,
            List<VerifiedItem> items,
            LocalDateTime expiresAt,
            LocalDateTime now) {
        Map<String, Object> facts = new LinkedHashMap<>();
        for (VerifiedItem item : items) {
            if (item.successful()) {
                facts.put(item.itemKey(), item.command().result().safeFacts());
            }
        }
        String factsJson = writeCanonicalJson(facts, "安全事实");
        if (factsJson.getBytes(StandardCharsets.UTF_8).length > maxFactBytes) {
            throw badRequest("安全小事实超过容量限制，请将大明细保存到ResultArtifact");
        }

        long successCount = items.stream().filter(VerifiedItem::successful).count();
        String status = successCount == items.size()
                ? "COMPLETE"
                : successCount == 0 ? "FAILED" : "PARTIAL_SUCCESS";
        boolean dataComplete = "COMPLETE".equals(status)
                && items.stream().allMatch(item -> item.command().result().dataComplete());

        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId(UUID.randomUUID().toString().replace("-", ""));
        snapshot.setSessionId(command.sessionId());
        snapshot.setUserId(command.userId());
        snapshot.setSubjectType(command.subjectType());
        snapshot.setSubjectId(command.subjectId());
        snapshot.setDatasetCode(command.datasetCode());
        String queryJson = writeCanonicalJson(command.canonicalQuery(), "查询条件");
        if (queryJson.getBytes(StandardCharsets.UTF_8).length > maxQueryBytes) {
            throw badRequest("规范查询条件超过容量限制，请缩小查询范围");
        }
        snapshot.setQueryJson(queryJson);
        snapshot.setQueryHash(command.queryHash());
        snapshot.setStatus(status);
        snapshot.setDataComplete(dataComplete);
        snapshot.setFactsJson(factsJson);
        snapshot.setConfigChecksum(dataset.configChecksum());
        snapshot.setFieldPolicyChecksum(dataset.fieldPolicyChecksum());
        snapshot.setSourceSnapshotId(command.sourceSnapshotId());
        snapshot.setExpiresAt(expiresAt);
        snapshot.setCreatedAt(now);
        snapshot.setCompletedAt(now);
        return snapshot;
    }

    private BusinessSnapshotItem toEntity(
            String snapshotId,
            VerifiedItem verified,
            LocalDateTime now) {
        ItemCommand command = verified.command();
        DatasetExecutionResult result = command.result();
        int total = nonNegative(command.totalCount(), "totalCount");
        int success = nonNegative(command.successCount(), "successCount");
        int failure = nonNegative(command.failureCount(), "failureCount");
        if ((long) success + failure > total) {
            throw badRequest("成功和失败数量之和不能大于总数");
        }

        BusinessSnapshotItem entity = new BusinessSnapshotItem();
        entity.setSnapshotId(snapshotId);
        entity.setItemKey(verified.itemKey());
        entity.setWorkflowCode(result.source().queryWorkflowCode());
        entity.setWorkflowVersionId(result.source().queryWorkflowVersionId());
        if (verified.run() != null) {
            entity.setWorkflowVersionNo(verified.run().getWorkflowVersionNo());
            entity.setWorkflowConfigChecksum(verified.run().getConfigChecksum());
            entity.setWorkflowRunId(verified.run().getRunId());
        }
        entity.setResultArtifactId(optionalText(result.resultArtifactId(), 32, "resultArtifactId"));
        entity.setStatus(verified.persistedStatus());
        entity.setAssociationType(command.associationType() == null
                ? null
                : command.associationType().name());
        entity.setTotalCount(total);
        entity.setSuccessCount(success);
        entity.setFailureCount(failure);
        entity.setSafeErrorCode(optionalText(result.safeErrorCode(), 128, "safeErrorCode"));
        entity.setSafeErrorMessage(optionalText(result.safeMessage(), 1000, "safeMessage"));
        entity.setCreatedAt(now);
        return entity;
    }

    private String writeCanonicalJson(Object value, String label) {
        try {
            return objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, label + "无法安全序列化", exception);
        }
    }

    private boolean isSuccessful(DatasetExecutionStatus status) {
        return status == DatasetExecutionStatus.SUCCESS || status == DatasetExecutionStatus.EMPTY;
    }

    private String persistedStatus(DatasetExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> "SUCCESS";
            case EMPTY -> "NO_DATA";
            case FAILED -> "FAILED";
            case TIMEOUT -> "TIMEOUT";
            default -> throw badRequest("不支持的快照执行项状态");
        };
    }

    private int nonNegative(Integer value, String field) {
        int normalized = value == null ? 0 : value;
        if (normalized < 0) {
            throw badRequest(field + "不能小于0");
        }
        return normalized;
    }

    private String requireText(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value) || !value.equals(value.trim())) {
            throw badRequest(field + "不能为空或包含首尾空白");
        }
        if (value.length() > maxLength) {
            throw badRequest(field + "长度不能超过" + maxLength);
        }
        return value;
    }

    private String optionalText(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return requireText(value, maxLength, field);
    }

    private String requireChecksum(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw badRequest(field + "必须是64位SHA-256十六进制");
        }
        return value;
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private BusinessException internal(String message) {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, message);
    }

    private record ValidatedCommand(
            String userId,
            String sessionId,
            String subjectType,
            String subjectId,
            String datasetCode,
            Map<String, Object> canonicalQuery,
            String queryHash,
            String sourceSnapshotId,
            List<ItemCommand> items) {
    }

    private record DatasetContext(
            String datasetCode,
            String workflowCode,
            String configChecksum,
            String fieldPolicyChecksum,
            int ttlMinutes,
            FactChannels factChannels,
            List<FieldPolicy> fieldPolicies) {
    }

    private record FieldPolicyContext(
            FactChannels channels,
            List<FieldPolicy> policies) {
    }

    private record FactChannels(
            Set<String> calculation,
            Set<String> display,
            Set<String> export,
            Set<String> model) {
    }

    private record VerifiedItem(
            ItemCommand command,
            String itemKey,
            String persistedStatus,
            WorkflowRun run,
            ResultArtifact artifact) {

        private boolean successful() {
            return "SUCCESS".equals(persistedStatus) || "NO_DATA".equals(persistedStatus);
        }
    }
}
