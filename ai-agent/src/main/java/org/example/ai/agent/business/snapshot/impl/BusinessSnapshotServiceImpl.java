package org.example.ai.agent.business.snapshot.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 安全业务快照持久化实现。
 */
@Service
public class BusinessSnapshotServiceImpl implements BusinessSnapshotService {

    private static final int MAX_TTL_MINUTES = 24 * 60;
    private static final Set<String> SAFE_FACT_CHANNELS = Set.of(
            "calculation",
            "display",
            "export",
            "model"
    );

    private final BusinessSnapshotMapper snapshotMapper;
    private final BusinessSnapshotItemMapper itemMapper;
    private final ResultArtifactMapper artifactMapper;
    private final WorkflowRunMapper workflowRunMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public BusinessSnapshotServiceImpl(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ResultArtifactMapper artifactMapper,
            WorkflowRunMapper workflowRunMapper,
            ObjectMapper objectMapper) {
        this(
                snapshotMapper,
                itemMapper,
                artifactMapper,
                workflowRunMapper,
                objectMapper,
                Clock.systemDefaultZone()
        );
    }

    /**
     * 可控时钟构造器用于验证有效期边界，生产环境仍由 Spring 使用上方构造器。
     */
    public BusinessSnapshotServiceImpl(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ResultArtifactMapper artifactMapper,
            WorkflowRunMapper workflowRunMapper,
            ObjectMapper objectMapper,
            Clock clock) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper不能为空");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper不能为空");
        this.artifactMapper = Objects.requireNonNull(artifactMapper, "artifactMapper不能为空");
        this.workflowRunMapper = Objects.requireNonNull(workflowRunMapper, "workflowRunMapper不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
    }

    /**
     * 元数据和全部执行项必须在同一事务完成，任何一项失败都不能留下半个快照。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public BusinessSnapshot create(CreateCommand command) {
        SnapshotContext context = validateCommand(command);
        LocalDateTime now = LocalDateTime.now(clock);
        validateSourceSnapshot(command.sourceSnapshotId(), context, now);

        List<VerifiedItem> verifiedItems = verifyItems(command.items(), context, now);
        if (verifiedItems.stream().noneMatch(VerifiedItem::successfulExecution)) {
            throw badRequest("快照至少需要一个已校验的成功或无数据执行项");
        }

        LocalDateTime expiresAt = calculateExpiry(context.ttlMinutes(), verifiedItems, now);
        BusinessSnapshot snapshot = buildSnapshot(
                command,
                context,
                verifiedItems,
                now,
                expiresAt
        );
        if (snapshotMapper.insert(snapshot) != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "业务快照写入失败");
        }
        for (VerifiedItem verified : verifiedItems) {
            if (itemMapper.insert(toEntity(snapshot.getSnapshotId(), verified, now)) != 1) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "业务快照执行项写入失败");
            }
        }
        return snapshot;
    }

    private SnapshotContext validateCommand(CreateCommand command) {
        if (command == null) {
            throw badRequest("快照创建命令不能为空");
        }
        String userId = requireText(command.userId(), "userId不能为空");
        String sessionId = requireText(command.sessionId(), "sessionId不能为空");
        String subjectType = requireText(command.subjectType(), "subjectType不能为空");
        String subjectId = requireText(command.subjectId(), "subjectId不能为空");
        ReportDataset dataset = Objects.requireNonNull(command.dataset(), "dataset不能为空");
        String datasetCode = requireText(dataset.getDatasetCode(), "datasetCode不能为空");
        String workflowCode = requireText(
                dataset.getQueryWorkflowCode(),
                "queryWorkflowCode不能为空"
        );
        String configChecksum = requireChecksum(
                dataset.getConfigChecksum(),
                "数据集配置校验和不能为空"
        );
        String fieldPolicyChecksum = requireChecksum(
                dataset.getFieldPolicyChecksum(),
                "字段策略校验和不能为空"
        );
        if (!Boolean.TRUE.equals(dataset.getEnabled())) {
            throw badRequest("数据集已停用，不能创建新快照");
        }
        Integer ttlMinutes = dataset.getTtlMinutes();
        if (ttlMinutes == null || ttlMinutes < 1 || ttlMinutes > MAX_TTL_MINUTES) {
            throw badRequest("数据集TTL必须在1至1440分钟之间");
        }
        if (command.items().isEmpty()) {
            throw badRequest("快照执行项不能为空");
        }
        return new SnapshotContext(
                userId,
                sessionId,
                subjectType,
                subjectId,
                datasetCode,
                workflowCode,
                configChecksum,
                fieldPolicyChecksum,
                ttlMinutes
        );
    }

    private void validateSourceSnapshot(
            String sourceSnapshotId,
            SnapshotContext context,
            LocalDateTime now) {
        if (!StringUtils.hasText(sourceSnapshotId)) {
            return;
        }
        BusinessSnapshot source = snapshotMapper.selectOne(
                Wrappers.<BusinessSnapshot>lambdaQuery()
                        .eq(BusinessSnapshot::getSnapshotId, sourceSnapshotId)
                        .eq(BusinessSnapshot::getUserId, context.userId())
                        .eq(BusinessSnapshot::getSessionId, context.sessionId())
                        .last("LIMIT 1")
        );
        boolean usable = source != null
                && Objects.equals(source.getUserId(), context.userId())
                && Objects.equals(source.getSessionId(), context.sessionId())
                && Objects.equals(source.getSubjectType(), context.subjectType())
                && Objects.equals(source.getSubjectId(), context.subjectId())
                && Objects.equals(source.getDatasetCode(), context.datasetCode())
                && Objects.equals(source.getFieldPolicyChecksum(), context.fieldPolicyChecksum())
                && Set.of("COMPLETE", "PARTIAL_SUCCESS").contains(source.getStatus())
                && source.getExpiresAt() != null
                && source.getExpiresAt().isAfter(now);
        /*
         * 不区分来源快照不存在与归属不匹配，避免向当前用户泄露他人快照是否存在。
         */
        if (!usable) {
            throw badRequest("来源快照不可用，请重新查询业务数据");
        }
    }

    private List<VerifiedItem> verifyItems(
            List<ItemCommand> items,
            SnapshotContext context,
            LocalDateTime now) {
        Set<String> itemKeys = new HashSet<>();
        List<VerifiedItem> verified = new ArrayList<>(items.size());
        for (ItemCommand item : items) {
            if (item == null || item.result() == null) {
                throw badRequest("快照执行项及结果不能为空");
            }
            String itemKey = requireText(item.itemKey(), "itemKey不能为空");
            if (!itemKeys.add(itemKey)) {
                throw badRequest("itemKey重复：" + itemKey);
            }
            if (!Objects.equals(item.result().datasetCode(), context.datasetCode())) {
                throw badRequest("执行结果与快照数据集不一致");
            }
            DatasetExecutionStatus executionStatus = item.result().status();
            if (executionStatus == DatasetExecutionStatus.DENIED) {
                throw new BusinessException(
                        ErrorCode.FORBIDDEN,
                        "无权执行的数据集不能创建业务快照"
                );
            }
            if (!Set.of(
                    DatasetExecutionStatus.SUCCESS,
                    DatasetExecutionStatus.EMPTY,
                    DatasetExecutionStatus.FAILED,
                    DatasetExecutionStatus.TIMEOUT
            ).contains(executionStatus)) {
                throw badRequest("数据集执行项尚未进入可持久化终态");
            }
            validateSafeFacts(item.result());
            WorkflowRun run = resolveWorkflowRun(item.result(), context);
            ResultArtifact artifact = validateArtifact(item.result(), context, run, now);
            verified.add(new VerifiedItem(item, persistedStatus(executionStatus), run, artifact));
        }
        return List.copyOf(verified);
    }

    private void validateSafeFacts(DatasetExecutionResult result) {
        if (result.status() == DatasetExecutionStatus.FAILED
                || result.status() == DatasetExecutionStatus.TIMEOUT) {
            if (!result.safeFacts().isEmpty()) {
                throw badRequest("失败或超时执行项不能携带业务事实");
            }
            return;
        }
        if (!result.safeFacts().keySet().equals(SAFE_FACT_CHANNELS)) {
            throw badRequest("执行结果必须使用字段策略生成的四个安全事实通道");
        }
        for (Object channel : result.safeFacts().values()) {
            if (!(channel instanceof Map<?, ?>)) {
                throw badRequest("安全事实通道必须是Map");
            }
        }
    }

    private WorkflowRun resolveWorkflowRun(
            DatasetExecutionResult result,
            SnapshotContext context) {
        if (!StringUtils.hasText(result.workflowRunId())) {
            if (result.status() == DatasetExecutionStatus.SUCCESS
                    || result.status() == DatasetExecutionStatus.EMPTY) {
                throw badRequest("成功或无数据执行项缺少工作流运行引用");
            }
            return null;
        }
        WorkflowRun run = workflowRunMapper.selectOne(
                Wrappers.<WorkflowRun>lambdaQuery()
                        .eq(WorkflowRun::getRunId, result.workflowRunId())
                        .eq(WorkflowRun::getUserId, context.userId())
                        .eq(WorkflowRun::getWorkflowCode, context.workflowCode())
                        .last("LIMIT 1")
        );
        boolean sameExecution = run != null
                && Objects.equals(run.getUserId(), context.userId())
                && Objects.equals(run.getWorkflowCode(), context.workflowCode());
        if (!sameExecution) {
            throw badRequest("工作流运行引用不可用");
        }
        if ((result.status() == DatasetExecutionStatus.SUCCESS
                || result.status() == DatasetExecutionStatus.EMPTY)
                && !Set.of("SUCCESS", "PARTIAL_SUCCESS").contains(run.getStatus())) {
            throw badRequest("工作流运行状态与数据集成功结果不一致");
        }
        requireChecksum(run.getConfigChecksum(), "工作流配置校验和不能为空");
        return run;
    }

    private ResultArtifact validateArtifact(
            DatasetExecutionResult result,
            SnapshotContext context,
            WorkflowRun run,
            LocalDateTime now) {
        if (!StringUtils.hasText(result.resultArtifactId())) {
            return null;
        }
        if (run == null) {
            throw badRequest("结果制品缺少对应工作流运行引用");
        }
        ResultArtifact artifact = artifactMapper.selectOne(
                Wrappers.<ResultArtifact>lambdaQuery()
                        .eq(ResultArtifact::getId, result.resultArtifactId())
                        .eq(ResultArtifact::getUserId, context.userId())
                        .eq(ResultArtifact::getSessionId, context.sessionId())
                        .last("LIMIT 1")
        );
        boolean sameExecution = artifact != null
                && Objects.equals(artifact.getUserId(), context.userId())
                && Objects.equals(artifact.getSessionId(), context.sessionId())
                && Objects.equals(artifact.getStatus(), "COMPLETE")
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
            List<VerifiedItem> items,
            LocalDateTime now) {
        LocalDateTime expiresAt = now.plusHours(24);
        LocalDateTime datasetExpiry = now.plusMinutes(ttlMinutes);
        if (datasetExpiry.isBefore(expiresAt)) {
            expiresAt = datasetExpiry;
        }
        for (VerifiedItem item : items) {
            if (item.artifact() != null
                    && item.artifact().getExpiresAt().isBefore(expiresAt)) {
                expiresAt = item.artifact().getExpiresAt();
            }
        }
        return expiresAt;
    }

    private BusinessSnapshot buildSnapshot(
            CreateCommand command,
            SnapshotContext context,
            List<VerifiedItem> items,
            LocalDateTime now,
            LocalDateTime expiresAt) {
        Map<String, Object> facts = new LinkedHashMap<>();
        for (VerifiedItem item : items) {
            if (item.successfulExecution()) {
                facts.put(item.command().itemKey(), item.command().result().safeFacts());
            }
        }
        boolean allSuccessful = items.stream().allMatch(VerifiedItem::successfulExecution);
        boolean dataComplete = allSuccessful
                && items.stream().allMatch(item -> item.command().result().dataComplete());

        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId(UUID.randomUUID().toString().replace("-", ""));
        snapshot.setSessionId(context.sessionId());
        snapshot.setUserId(context.userId());
        snapshot.setSubjectType(context.subjectType());
        snapshot.setSubjectId(context.subjectId());
        snapshot.setDatasetCode(context.datasetCode());
        snapshot.setQueryJson(writeCanonicalJson(command.canonicalQuery(), "查询条件"));
        snapshot.setQueryHash(ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(command.canonicalQuery())
        ));
        snapshot.setStatus(allSuccessful ? "COMPLETE" : "PARTIAL_SUCCESS");
        snapshot.setDataComplete(dataComplete);
        snapshot.setFactsJson(writeCanonicalJson(facts, "安全事实"));
        snapshot.setConfigChecksum(context.configChecksum());
        snapshot.setFieldPolicyChecksum(context.fieldPolicyChecksum());
        snapshot.setSourceSnapshotId(normalizeOptional(command.sourceSnapshotId()));
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
        WorkflowRun run = verified.run();

        BusinessSnapshotItem item = new BusinessSnapshotItem();
        item.setSnapshotId(snapshotId);
        item.setItemKey(command.itemKey());
        if (run != null) {
            item.setWorkflowCode(run.getWorkflowCode());
            item.setWorkflowVersionId(run.getWorkflowVersionId());
            item.setWorkflowVersionNo(run.getWorkflowVersionNo());
            item.setWorkflowConfigChecksum(run.getConfigChecksum());
            item.setWorkflowRunId(run.getRunId());
        }
        item.setResultArtifactId(normalizeOptional(result.resultArtifactId()));
        item.setStatus(verified.persistedStatus());
        item.setAssociationType(normalizeOptional(command.associationType()));
        int totalCount = nonNegative(command.totalCount(), "totalCount");
        int successCount = nonNegative(command.successCount(), "successCount");
        int failureCount = nonNegative(command.failureCount(), "failureCount");
        if (successCount + failureCount > totalCount) {
            throw badRequest("成功和失败数量之和不能大于总数");
        }
        item.setTotalCount(totalCount);
        item.setSuccessCount(successCount);
        item.setFailureCount(failureCount);
        item.setSafeErrorCode(limit(result.safeErrorCode(), 128, "safeErrorCode"));
        item.setSafeErrorMessage(limit(result.safeMessage(), 1000, "safeMessage"));
        item.setCreatedAt(now);
        return item;
    }

    private String writeCanonicalJson(Object value, String label) {
        try {
            return objectMapper.writer()
                    .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    label + "无法安全序列化",
                    exception
            );
        }
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

    private Integer nonNegative(Integer value, String field) {
        int normalized = value == null ? 0 : value;
        if (normalized < 0) {
            throw badRequest(field + "不能小于0");
        }
        return normalized;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value) || !value.equals(value.trim())) {
            throw badRequest(message);
        }
        return value;
    }

    private String requireChecksum(String value, String message) {
        String checksum = requireText(value, message);
        if (checksum.length() > 64) {
            throw badRequest(message);
        }
        return checksum;
    }

    private String normalizeOptional(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String limit(String value, int maxLength, String field) {
        if (value == null) {
            return null;
        }
        if (value.length() > maxLength) {
            throw badRequest(field + "长度不能超过" + maxLength);
        }
        return value;
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private record SnapshotContext(
            String userId,
            String sessionId,
            String subjectType,
            String subjectId,
            String datasetCode,
            String workflowCode,
            String configChecksum,
            String fieldPolicyChecksum,
            int ttlMinutes) {
    }

    private record VerifiedItem(
            ItemCommand command,
            String persistedStatus,
            WorkflowRun run,
            ResultArtifact artifact) {

        private boolean successfulExecution() {
            return "SUCCESS".equals(persistedStatus) || "NO_DATA".equals(persistedStatus);
        }
    }
}
