package org.example.ai.agent.business.snapshot;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import java.nio.charset.StandardCharsets;

/**
 * 业务快照确定性匹配器。
 *
 * 这里只依据持久化事实、当前配置和复权结果做决定，模型不能覆盖匹配结果。
 */
@Service
public class BusinessSnapshotMatcher {

    public static final String GENERIC_REQUERY_REASON = "业务快照不可用，请重新查询";
    private static final int MAX_CANDIDATES = 50;
    private static final int MAX_ITEMS = 1000;
    private static final int MAX_QUERY_BYTES = 64 * 1024;
    private static final int MAX_FACT_BYTES = 256 * 1024;
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
        String requestedHash = ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(command.canonicalQuery())
        );
        List<ReportDatasetField> fields = fieldMapper.selectList(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .eq(ReportDatasetField::getDatasetId, grant.orElseThrow().datasetId())
                        .orderByAsc(ReportDatasetField::getDisplayOrder, ReportDatasetField::getId)
        );
        SnapshotMatchResult exact = findMatch(
                loadCandidates(command, now, requestedHash, true), command,
                grant.orElseThrow(), fields, now, requestedHash, true
        );
        if (exact != null) {
            return exact;
        }
        SnapshotMatchResult derived = findMatch(
                loadCandidates(command, now, requestedHash, false), command,
                grant.orElseThrow(), fields, now, requestedHash, false
        );
        return derived == null ? requery(GENERIC_REQUERY_REASON) : derived;
    }

    private List<BusinessSnapshot> loadCandidates(
            MatchCommand command,
            LocalDateTime now,
            String requestedHash,
            boolean exact) {
        LambdaQueryWrapper<BusinessSnapshot> query = Wrappers.<BusinessSnapshot>lambdaQuery()
                .eq(BusinessSnapshot::getUserId, command.userId())
                .eq(BusinessSnapshot::getSessionId, command.sessionId())
                .eq(BusinessSnapshot::getSubjectType, command.subjectType().name())
                .eq(BusinessSnapshot::getSubjectId, command.subjectId())
                .eq(BusinessSnapshot::getDatasetCode, command.datasetCode())
                .in(BusinessSnapshot::getStatus, REUSABLE_STATUS)
                .gt(BusinessSnapshot::getExpiresAt, now);
        if (exact) {
            query.eq(BusinessSnapshot::getQueryHash, requestedHash);
        } else {
            query.ne(BusinessSnapshot::getQueryHash, requestedHash);
        }
        List<BusinessSnapshot> snapshots = snapshotMapper.selectList(
                query.orderByDesc(BusinessSnapshot::getCompletedAt)
                        .last("LIMIT " + MAX_CANDIDATES)
        );
        return snapshots == null ? List.of() : snapshots;
    }

    private SnapshotMatchResult findMatch(
            List<BusinessSnapshot> snapshots,
            MatchCommand command,
            BusinessSnapshotAccessService.AccessGrant grant,
            List<ReportDatasetField> fields,
            LocalDateTime now,
            String requestedHash,
            boolean exactOnly) {
        List<BusinessSnapshot> ordered = snapshots.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(
                        BusinessSnapshot::getCompletedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();

        for (BusinessSnapshot snapshot : ordered) {
            if (!usable(snapshot, command, grant, now)) {
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
            if (!factsUsable(snapshot, items, fields, command)) {
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
            if (exactOnly) {
                continue;
            }
            boolean smallFactsOnly = items.stream().noneMatch(
                    item -> StringUtils.hasText(item.getResultArtifactId())
            );
            boolean hasData = items.stream().anyMatch(item ->
                    BusinessSnapshotItemStatus.SUCCESS.name().equals(item.getStatus())
            );
            if (smallFactsOnly && hasData && canDerive(
                    sourceQuery, command.canonicalQuery(), command.requestedGrain(),
                    command.requiredFactCodes(), fields
            )) {
                return new SnapshotMatchResult(
                        SnapshotMatchDecision.DERIVE, snapshot.getSnapshotId(), "来源范围覆盖目标且粒度充足"
                );
            }
        }
        return null;
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
                && command.associationType() != null
                && command.requiredFactCodes().size() <= MAX_ITEMS
                && command.requiredFactCodes().stream().allMatch(
                code -> StringUtils.hasText(code) && code.length() <= 128
        )
                && grainConsistent(command.requestedGrain(), command.canonicalQuery());
    }

    private boolean grainConsistent(
            String requestedGrain,
            Map<String, Object> query) {
        String queryGrain = query == null ? null : Objects.toString(query.get("grain"), null);
        if (!StringUtils.hasText(requestedGrain) && !StringUtils.hasText(queryGrain)) {
            return true;
        }
        Optional<TimeGrain> requested = TimeGrain.parse(requestedGrain);
        Optional<TimeGrain> canonical = TimeGrain.parse(queryGrain);
        return requested.isPresent()
                && canonical.isPresent()
                && requested.get() == canonical.get();
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
                        .last("LIMIT " + (MAX_ITEMS + 1))
        );
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            return List.of();
        }
        Set<String> artifactIds = new java.util.LinkedHashSet<>();
        for (BusinessSnapshotItem item : items) {
            if (item == null
                    || !Objects.equals(item.getAssociationType(), command.associationType().name())
                    || !Set.of(
                    BusinessSnapshotItemStatus.SUCCESS.name(),
                    BusinessSnapshotItemStatus.NO_DATA.name()
            ).contains(item.getStatus())
            ) {
                return List.of();
            }
            if (StringUtils.hasText(item.getResultArtifactId())) {
                artifactIds.add(item.getResultArtifactId());
            }
        }
        return artifactsUsable(artifactIds, command, now)
                ? List.copyOf(items)
                : List.of();
    }

    private boolean factsUsable(
            BusinessSnapshot snapshot,
            List<BusinessSnapshotItem> items,
            List<ReportDatasetField> fields,
            MatchCommand command) {
        if (command.requiredFactCodes().isEmpty()) {
            return true;
        }
        if (command.requiredChannels().isEmpty() || fields == null || fields.isEmpty()) {
            return false;
        }
        Map<String, Object> root = readJsonMap(snapshot.getFactsJson());
        if (root == null) {
            return false;
        }
        Map<String, ReportDatasetField> byCode = new LinkedHashMap<>();
        for (ReportDatasetField field : fields) {
            if (field == null || !StringUtils.hasText(field.getFactCode())
                    || byCode.putIfAbsent(field.getFactCode(), field) != null) {
                return false;
            }
        }
        for (BusinessSnapshotItem item : items) {
            if (BusinessSnapshotItemStatus.NO_DATA.name().equals(item.getStatus())) {
                continue;
            }
            Object envelopeValue = root.get(item.getItemKey());
            if (!(envelopeValue instanceof Map<?, ?> envelope)) {
                return false;
            }
            for (SnapshotFactChannel channel : command.requiredChannels()) {
                Object channelValue = envelope.get(channel.jsonName());
                if (!(channelValue instanceof Map<?, ?> channelFacts)) {
                    return false;
                }
                for (String code : command.requiredFactCodes()) {
                    ReportDatasetField field = byCode.get(code);
                    if (field == null || !allowedInChannel(field, channel)
                            || !channelFacts.containsKey(code)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean allowedInChannel(
            ReportDatasetField field,
            SnapshotFactChannel channel) {
        return switch (channel) {
            case CALCULATION -> Boolean.TRUE.equals(field.getCalculable());
            case DISPLAY -> Boolean.TRUE.equals(field.getDisplayable());
            case EXPORT -> Boolean.TRUE.equals(field.getExportable());
            case MODEL -> Boolean.TRUE.equals(field.getModelVisible());
        };
    }

    private boolean artifactsUsable(
            Set<String> artifactIds,
            MatchCommand command,
            LocalDateTime now) {
        if (artifactIds.isEmpty()) {
            return true;
        }
        List<ResultArtifact> artifacts = artifactMapper.selectList(
                Wrappers.<ResultArtifact>lambdaQuery()
                        .in(ResultArtifact::getId, artifactIds)
                        .eq(ResultArtifact::getUserId, command.userId())
                        .eq(ResultArtifact::getSessionId, command.sessionId())
                        .eq(ResultArtifact::getStatus, "COMPLETE")
        );
        if (artifacts == null || artifacts.size() != artifactIds.size()) {
            return false;
        }
        Set<String> verified = new java.util.HashSet<>();
        for (ResultArtifact artifact : artifacts) {
            if (artifact == null || !artifactIds.contains(artifact.getId())
                    || !verified.add(artifact.getId())
                    || !Objects.equals(artifact.getUserId(), command.userId())
                    || !Objects.equals(artifact.getSessionId(), command.sessionId())
                    || !"COMPLETE".equals(artifact.getStatus())
                    || artifact.getExpiresAt() == null
                    || !artifact.getExpiresAt().isAfter(now)) {
                return false;
            }
        }
        return verified.equals(artifactIds);
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
        if (queryJson.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
            return null;
        }
        try {
            return objectMapper.readValue(queryJson, new TypeReference<>() { });
        } catch (Exception exception) {
            return null;
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_FACT_BYTES) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
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
            Set<SnapshotFactChannel> requiredChannels,
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
            requiredChannels = requiredChannels == null
                    ? Set.of()
                    : Set.copyOf(requiredChannels);
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
                boolean hasStart = query.containsKey("startDate");
                boolean hasEnd = query.containsKey("endDate");
                boolean hasYear = query.containsKey("year");
                boolean hasQuarter = query.containsKey("quarter");
                if (hasStart || hasEnd) {
                    if (!hasStart || !hasEnd || hasYear || hasQuarter) {
                        return Optional.empty();
                    }
                    LocalDate start = LocalDate.parse(Objects.toString(query.get("startDate"), ""));
                    LocalDate end = LocalDate.parse(Objects.toString(query.get("endDate"), ""));
                    return start.isAfter(end) ? Optional.empty() : Optional.of(new DateRange(start, end));
                }
                if (!hasYear || (hasQuarter && !hasYear)) {
                    return Optional.empty();
                }
                Object yearValue = query.get("year");
                int year = yearValue instanceof Number number
                        ? number.intValue()
                        : Integer.parseInt(Objects.toString(yearValue, ""));
                if (year < 1900 || year > 9999) {
                    return Optional.empty();
                }
                if (hasQuarter) {
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
