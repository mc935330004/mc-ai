package org.example.ai.agent.business.snapshot;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshotItem;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotItemMapper;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.workflow.answer.artifact.entity.ResultArtifact;
import org.example.ai.agent.workflow.answer.artifact.mapper.ResultArtifactMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 业务快照确定性匹配器。
 *
 * 这里只依据持久化事实、当前配置和复权结果做决定，模型不能覆盖匹配结果。
 */
@Service
public class BusinessSnapshotMatcher {

    public static final String GENERIC_REQUERY_REASON = "业务快照不可用，请重新查询";
    private static final Set<String> REUSABLE_STATUS = Set.of("COMPLETE", "PARTIAL_SUCCESS");

    private final BusinessSnapshotMapper snapshotMapper;
    private final BusinessSnapshotItemMapper itemMapper;
    private final ReportDatasetFieldMapper fieldMapper;
    private final ResultArtifactMapper artifactMapper;
    private final BusinessSnapshotAccessService accessService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public BusinessSnapshotMatcher(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ReportDatasetFieldMapper fieldMapper,
            ResultArtifactMapper artifactMapper,
            BusinessSnapshotAccessService accessService,
            ObjectMapper objectMapper) {
        this(snapshotMapper, itemMapper, fieldMapper, artifactMapper,
                accessService, objectMapper, Clock.systemDefaultZone());
    }

    /** 测试构造器允许固定时间边界。 */
    public BusinessSnapshotMatcher(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ReportDatasetFieldMapper fieldMapper,
            ResultArtifactMapper artifactMapper,
            BusinessSnapshotAccessService accessService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper不能为空");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper不能为空");
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper不能为空");
        this.artifactMapper = Objects.requireNonNull(artifactMapper, "artifactMapper不能为空");
        this.accessService = Objects.requireNonNull(accessService, "accessService不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
    }

    public SnapshotMatchResult match(MatchCommand command) {
        if (!valid(command) || command.refreshRequested()) {
            return requery(command != null && command.refreshRequested()
                    ? "用户已明确要求刷新"
                    : GENERIC_REQUERY_REASON);
        }
        Optional<BusinessSnapshotAccessService.AccessGrant> grant = accessService.reauthorize(
                new BusinessSnapshotAccessService.AccessCommand(
                        command.agentRunId(), command.userId(), command.sessionId(),
                        command.authorization(), command.secureContext(), command.datasetCode(),
                        command.subjectType(), command.subjectId(), command.canonicalQuery()
                )
        );
        if (grant.isEmpty()) {
            return requery(GENERIC_REQUERY_REASON);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<BusinessSnapshot> snapshots = snapshotMapper.selectList(
                Wrappers.<BusinessSnapshot>lambdaQuery()
                        .eq(BusinessSnapshot::getUserId, command.userId())
                        .eq(BusinessSnapshot::getSessionId, command.sessionId())
                        .eq(BusinessSnapshot::getSubjectType, command.subjectType().name())
                        .eq(BusinessSnapshot::getSubjectId, command.subjectId())
                        .eq(BusinessSnapshot::getDatasetCode, command.datasetCode())
                        .in(BusinessSnapshot::getStatus, REUSABLE_STATUS)
                        .gt(BusinessSnapshot::getExpiresAt, now)
                        .orderByDesc(BusinessSnapshot::getCompletedAt)
        );
        if (snapshots == null || snapshots.isEmpty()) {
            return requery(GENERIC_REQUERY_REASON);
        }
        List<ReportDatasetField> fields = fieldMapper.selectList(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .eq(ReportDatasetField::getDatasetId, grant.orElseThrow().datasetId())
                        .orderByAsc(ReportDatasetField::getDisplayOrder, ReportDatasetField::getId)
        );
        String requestedHash = ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(command.canonicalQuery())
        );
        List<BusinessSnapshot> ordered = snapshots.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(
                        BusinessSnapshot::getCompletedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();

        for (BusinessSnapshot snapshot : ordered) {
            if (!usable(snapshot, command, grant.orElseThrow(), now)) {
                continue;
            }
            List<BusinessSnapshotItem> items = loadUsableItems(snapshot, command, now);
            if (items.isEmpty()) {
                continue;
            }
            Map<String, Object> sourceQuery = readQuery(snapshot.getQueryJson());
            if (sourceQuery == null) {
                continue;
            }
            String actualSourceHash = ContentHashUtils.sha256(
                    ReportDatasetValidator.canonicalSafeValue(sourceQuery)
            );
            if (!Objects.equals(snapshot.getQueryHash(), actualSourceHash)) {
                continue;
            }
            if (Objects.equals(actualSourceHash, requestedHash)
                    && Objects.equals(
                    ReportDatasetValidator.canonicalSafeValue(sourceQuery),
                    ReportDatasetValidator.canonicalSafeValue(command.canonicalQuery()))) {
                return new SnapshotMatchResult(
                        SnapshotMatchDecision.REUSE, snapshot.getSnapshotId(), "查询条件完全一致"
                );
            }
            boolean smallFactsOnly = items.stream().noneMatch(
                    item -> StringUtils.hasText(item.getResultArtifactId())
            );
            if (smallFactsOnly && canDerive(
                    sourceQuery, command.canonicalQuery(), command.requestedGrain(),
                    command.requiredFactCodes(), fields
            )) {
                return new SnapshotMatchResult(
                        SnapshotMatchDecision.DERIVE, snapshot.getSnapshotId(), "来源范围覆盖目标且粒度充足"
                );
            }
        }
        return requery(GENERIC_REQUERY_REASON);
    }

    private boolean valid(MatchCommand command) {
        return command != null
                && StringUtils.hasText(command.agentRunId())
                && StringUtils.hasText(command.userId())
                && StringUtils.hasText(command.sessionId())
                && StringUtils.hasText(command.authorization())
                && command.subjectType() != null
                && StringUtils.hasText(command.subjectId())
                && StringUtils.hasText(command.datasetCode())
                && command.associationType() != null;
    }

    private boolean usable(
            BusinessSnapshot snapshot,
            MatchCommand command,
            BusinessSnapshotAccessService.AccessGrant grant,
            LocalDateTime now) {
        return Objects.equals(snapshot.getUserId(), command.userId())
                && Objects.equals(snapshot.getSessionId(), command.sessionId())
                && Objects.equals(snapshot.getSubjectType(), command.subjectType().name())
                && Objects.equals(snapshot.getSubjectId(), command.subjectId())
                && Objects.equals(snapshot.getDatasetCode(), command.datasetCode())
                && Objects.equals(snapshot.getConfigChecksum(), grant.configChecksum())
                && Objects.equals(snapshot.getFieldPolicyChecksum(), grant.fieldPolicyChecksum())
                && REUSABLE_STATUS.contains(snapshot.getStatus())
                && snapshot.getExpiresAt() != null
                && snapshot.getExpiresAt().isAfter(now);
    }

    private List<BusinessSnapshotItem> loadUsableItems(
            BusinessSnapshot snapshot,
            MatchCommand command,
            LocalDateTime now) {
        List<BusinessSnapshotItem> items = itemMapper.selectList(
                Wrappers.<BusinessSnapshotItem>lambdaQuery()
                        .eq(BusinessSnapshotItem::getSnapshotId, snapshot.getSnapshotId())
                        .orderByAsc(BusinessSnapshotItem::getId)
        );
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        for (BusinessSnapshotItem item : items) {
            if (item == null
                    || !Objects.equals(item.getAssociationType(), command.associationType().name())
                    || !Set.of("SUCCESS", "EMPTY").contains(item.getStatus())
                    || !artifactUsable(item.getResultArtifactId(), command, now)) {
                return List.of();
            }
        }
        return List.copyOf(items);
    }

    private boolean artifactUsable(
            String artifactId,
            MatchCommand command,
            LocalDateTime now) {
        if (!StringUtils.hasText(artifactId)) {
            return true;
        }
        ResultArtifact artifact = artifactMapper.selectOne(
                Wrappers.<ResultArtifact>lambdaQuery()
                        .eq(ResultArtifact::getId, artifactId)
                        .eq(ResultArtifact::getUserId, command.userId())
                        .eq(ResultArtifact::getSessionId, command.sessionId())
                        .eq(ResultArtifact::getStatus, "COMPLETE")
                        .last("LIMIT 1")
        );
        return artifact != null
                && Objects.equals(artifact.getUserId(), command.userId())
                && Objects.equals(artifact.getSessionId(), command.sessionId())
                && "COMPLETE".equals(artifact.getStatus())
                && artifact.getExpiresAt() != null
                && artifact.getExpiresAt().isAfter(now);
    }

    boolean canDerive(
            Map<String, Object> sourceQuery,
            Map<String, Object> targetQuery,
            String requestedGrain,
            Set<String> requiredFactCodes,
            List<ReportDatasetField> fields) {
        Optional<DateRange> source = DateRange.parse(sourceQuery);
        Optional<DateRange> target = DateRange.parse(targetQuery);
        Optional<TimeGrain> targetGrain = TimeGrain.parse(requestedGrain);
        if (source.isEmpty() || target.isEmpty() || targetGrain.isEmpty()
                || requiredFactCodes == null || requiredFactCodes.isEmpty()
                || !sameNonTemporalConditions(sourceQuery, targetQuery)
                || !source.orElseThrow().strictlyCovers(target.orElseThrow())
                || fields == null || fields.isEmpty()) {
            return false;
        }
        Map<String, ReportDatasetField> fieldsByCode = new LinkedHashMap<>();
        for (ReportDatasetField field : fields) {
            if (field != null && StringUtils.hasText(field.getFactCode())) {
                fieldsByCode.putIfAbsent(field.getFactCode(), field);
            }
        }
        for (String requiredFactCode : requiredFactCodes) {
            ReportDatasetField field = fieldsByCode.get(requiredFactCode);
            if (field == null
                    || !Boolean.TRUE.equals(field.getFilterable())
                    || !Boolean.TRUE.equals(field.getCalculable())
                    || !"DATE_RECORD_LIST".equals(field.getFactType())
                    || !TimeGrain.parse(field.getGrain())
                    .map(grain -> grain.supports(targetGrain.orElseThrow()))
                    .orElse(false)) {
                return false;
            }
        }
        return true;
    }

    private boolean sameNonTemporalConditions(
            Map<String, Object> sourceQuery,
            Map<String, Object> targetQuery) {
        Set<String> temporalKeys = Set.of(
                "year", "quarter", "startDate", "endDate", "grain"
        );
        Map<String, Object> sourceOther = new LinkedHashMap<>(sourceQuery);
        Map<String, Object> targetOther = new LinkedHashMap<>(targetQuery);
        temporalKeys.forEach(sourceOther::remove);
        temporalKeys.forEach(targetOther::remove);
        return Objects.equals(
                ReportDatasetValidator.canonicalSafeValue(sourceOther),
                ReportDatasetValidator.canonicalSafeValue(targetOther)
        );
    }

    private Map<String, Object> readQuery(String queryJson) {
        if (!StringUtils.hasText(queryJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(queryJson, new TypeReference<>() { });
        } catch (Exception exception) {
            return null;
        }
    }

    private SnapshotMatchResult requery(String reason) {
        return new SnapshotMatchResult(SnapshotMatchDecision.REQUERY, null, reason);
    }

    public record MatchCommand(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            BusinessSubjectType subjectType,
            String subjectId,
            String datasetCode,
            Map<String, Object> canonicalQuery,
            AssociationType associationType,
            String requestedGrain,
            Set<String> requiredFactCodes,
            boolean refreshRequested) {

        @SuppressWarnings("unchecked")
        public MatchCommand {
            secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    secureContext == null ? Map.of() : secureContext
            );
            canonicalQuery = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    canonicalQuery == null ? Map.of() : canonicalQuery
            );
            requiredFactCodes = requiredFactCodes == null
                    ? Set.of()
                    : Set.copyOf(requiredFactCodes);
        }

        /** 日志仅保留路由摘要，认证、上下文和查询值必须隐藏。 */
        @Override
        public String toString() {
            return "MatchCommand[userId=" + userId
                    + ", sessionId=" + sessionId
                    + ", datasetCode=" + datasetCode
                    + ", subjectType=" + subjectType
                    + ", refreshRequested=" + refreshRequested
                    + ", authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", secureContextSize=" + secureContext.size()
                    + ", canonicalQuerySize=" + canonicalQuery.size() + ']';
        }
    }

    enum TimeGrain {
        DAY(1), MONTH(2), QUARTER(3), YEAR(4);

        private final int level;

        TimeGrain(int level) {
            this.level = level;
        }

        boolean supports(TimeGrain requested) {
            return level <= requested.level;
        }

        static Optional<TimeGrain> parse(String value) {
            if (!StringUtils.hasText(value)) {
                return Optional.empty();
            }
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "DAY", "DAILY", "DATE" -> Optional.of(DAY);
                case "MONTH", "MONTHLY" -> Optional.of(MONTH);
                case "QUARTER", "QUARTERLY" -> Optional.of(QUARTER);
                case "YEAR", "ANNUAL", "YEARLY" -> Optional.of(YEAR);
                default -> Optional.empty();
            };
        }
    }

    record DateRange(LocalDate start, LocalDate end) {

        boolean strictlyCovers(DateRange target) {
            return !target.start.isBefore(start)
                    && !target.end.isAfter(end)
                    && (target.start.isAfter(start) || target.end.isBefore(end));
        }

        static Optional<DateRange> parse(Map<String, Object> query) {
            if (query == null || query.isEmpty()) {
                return Optional.empty();
            }
            try {
                if (query.containsKey("startDate") && query.containsKey("endDate")) {
                    LocalDate start = LocalDate.parse(Objects.toString(query.get("startDate"), ""));
                    LocalDate end = LocalDate.parse(Objects.toString(query.get("endDate"), ""));
                    return start.isAfter(end) ? Optional.empty() : Optional.of(new DateRange(start, end));
                }
                Object yearValue = query.get("year");
                int year = yearValue instanceof Number number
                        ? number.intValue()
                        : Integer.parseInt(Objects.toString(yearValue, ""));
                if (year < 1900 || year > 9999) {
                    return Optional.empty();
                }
                if (query.containsKey("quarter")) {
                    int quarter = Integer.parseInt(Objects.toString(query.get("quarter"), ""));
                    if (quarter < 1 || quarter > 4) {
                        return Optional.empty();
                    }
                    LocalDate start = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
                    return Optional.of(new DateRange(start, start.plusMonths(3).minusDays(1)));
                }
                return Optional.of(new DateRange(
                        LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)
                ));
            } catch (DateTimeException | NumberFormatException exception) {
                return Optional.empty();
            }
        }
    }
}
