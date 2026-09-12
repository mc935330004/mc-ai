package org.example.ai.agent.business.snapshot;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 在组合报告建任务前精确复核业务快照引用及当前数据集访问权。
 *
 * 任一归属、查询、配置、策略、状态或有效期不一致都返回 false，调用方不得创建报告任务。
 */
@Service
public class BusinessSnapshotReferenceValidationService {

    private static final Set<String> USABLE_STATUSES = Set.of(
            "COMPLETE", "PARTIAL_SUCCESS"
    );

    private final BusinessSnapshotMapper snapshotMapper;
    private final BusinessSnapshotAccessService accessService;
    private final Clock clock;

    @Autowired
    public BusinessSnapshotReferenceValidationService(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotAccessService accessService) {
        this(snapshotMapper, accessService, Clock.systemDefaultZone());
    }

    /** 测试构造器允许固定有效期边界。 */
    BusinessSnapshotReferenceValidationService(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotAccessService accessService,
            Clock clock) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper不能为空");
        this.accessService = Objects.requireNonNull(accessService, "accessService不能为空");
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
    }

    /** 快照精确匹配且当前访问工作流仍允许时才返回 true。 */
    public boolean validate(ValidationCommand command) {
        if (!valid(command)) {
            return false;
        }
        try {
            BusinessSnapshot snapshot = snapshotMapper.selectById(command.snapshotId());
            if (!snapshotMatches(snapshot, command, LocalDateTime.now(clock))) {
                return false;
            }
            return accessService.reauthorize(new BusinessSnapshotAccessService.AccessCommand(
                            command.agentRunId(), command.userId(), command.sessionId(),
                            command.authorization(), command.secureContext(), command.datasetCode(),
                            command.subjectType(), command.subjectId(), command.canonicalQuery()
                    ))
                    .filter(grant -> Objects.equals(
                            grant.configChecksum(), snapshot.getConfigChecksum()
                    ))
                    .filter(grant -> Objects.equals(
                            grant.fieldPolicyChecksum(), snapshot.getFieldPolicyChecksum()
                    ))
                    .filter(grant -> Objects.equals(
                            grant.fieldPolicyChecksum(), command.fieldPolicyChecksum()
                    ))
                    .isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean snapshotMatches(
            BusinessSnapshot snapshot,
            ValidationCommand command,
            LocalDateTime now) {
        String queryHash = ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(command.canonicalQuery())
        );
        return snapshot != null
                && Objects.equals(snapshot.getSnapshotId(), command.snapshotId())
                && Objects.equals(snapshot.getUserId(), command.userId())
                && Objects.equals(snapshot.getSessionId(), command.sessionId())
                && Objects.equals(snapshot.getSubjectType(), command.subjectType().name())
                && Objects.equals(snapshot.getSubjectId(), command.subjectId())
                && Objects.equals(snapshot.getDatasetCode(), command.datasetCode())
                && Objects.equals(snapshot.getQueryHash(), queryHash)
                && Objects.equals(snapshot.getFieldPolicyChecksum(), command.fieldPolicyChecksum())
                && USABLE_STATUSES.contains(snapshot.getStatus())
                && snapshot.getExpiresAt() != null
                && snapshot.getExpiresAt().isAfter(now);
    }

    private boolean valid(ValidationCommand command) {
        return command != null
                && StringUtils.hasText(command.agentRunId())
                && StringUtils.hasText(command.userId())
                && StringUtils.hasText(command.sessionId())
                && StringUtils.hasText(command.authorization())
                && command.subjectType() != null
                && StringUtils.hasText(command.subjectId())
                && StringUtils.hasText(command.datasetCode())
                && StringUtils.hasText(command.snapshotId())
                && StringUtils.hasText(command.fieldPolicyChecksum());
    }

    public record ValidationCommand(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            BusinessSubjectType subjectType,
            String subjectId,
            String datasetCode,
            String snapshotId,
            String fieldPolicyChecksum,
            Map<String, Object> canonicalQuery) {

        @SuppressWarnings("unchecked")
        public ValidationCommand {
            secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    secureContext == null ? Map.of() : secureContext
            );
            canonicalQuery = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    canonicalQuery == null ? Map.of() : canonicalQuery
            );
        }

        /** 日志不输出认证、主体、快照、策略或查询值。 */
        @Override
        public String toString() {
            return "ValidationCommand[userId=" + userId
                    + ", sessionId=" + sessionId
                    + ", subjectType=" + subjectType
                    + ", datasetCode=" + datasetCode
                    + ", authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", snapshotPresent=" + StringUtils.hasText(snapshotId)
                    + ", fieldPolicyPresent=" + StringUtils.hasText(fieldPolicyChecksum)
                    + ", secureContextSize=" + secureContext.size()
                    + ", canonicalQuerySize=" + canonicalQuery.size() + ']';
        }
    }
}
