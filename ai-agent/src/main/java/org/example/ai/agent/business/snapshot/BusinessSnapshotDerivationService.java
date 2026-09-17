package org.example.ai.agent.business.snapshot;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.person.PersonBusinessQueryService;
import org.example.ai.agent.business.person.PersonBusinessFactAggregator;
import org.example.ai.agent.business.person.PersonBusinessQueryService.AssociationSummary;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.example.ai.agent.business.person.PersonBusinessQueryService.ProjectAssociationContext;
import org.example.ai.agent.business.person.ProjectRecordAssociationService;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshotItem;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotItemMapper;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

/**
 * 从已复权的安全快照确定性派生更窄时间范围。
 *
 * 本服务不接受调用方提供的事实，也不读取来源接口原始响应；所有子事实均来自锁定后的安全快照。
 */
@Service
public class BusinessSnapshotDerivationService {

    private static final Set<String> CHANNELS = Set.of(
            "calculation", "display", "export", "model"
    );
    private static final int MAX_QUERY_BYTES = 64 * 1024;
    private static final int MAX_FACT_BYTES = 256 * 1024;
    private static final int MAX_ITEMS = 1000;

    private final BusinessSnapshotMapper snapshotMapper;
    private final BusinessSnapshotItemMapper itemMapper;
    private final ReportDatasetFieldMapper fieldMapper;
    private final BusinessSnapshotAccessService accessService;
    private final BusinessFactSanitizer factSanitizer;
    private final ProjectRecordAssociationService associationService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public BusinessSnapshotDerivationService(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ReportDatasetFieldMapper fieldMapper,
            BusinessSnapshotAccessService accessService,
            BusinessFactSanitizer factSanitizer,
            ProjectRecordAssociationService associationService,
            ObjectMapper objectMapper) {
        this(snapshotMapper, itemMapper, fieldMapper, accessService, factSanitizer, associationService,
                objectMapper, Clock.systemDefaultZone());
    }

    /** 测试构造器允许固定时钟。 */
    public BusinessSnapshotDerivationService(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ReportDatasetFieldMapper fieldMapper,
            BusinessSnapshotAccessService accessService,
            BusinessFactSanitizer factSanitizer,
            ProjectRecordAssociationService associationService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper不能为空");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper不能为空");
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper不能为空");
        this.accessService = Objects.requireNonNull(accessService, "accessService不能为空");
        this.factSanitizer = Objects.requireNonNull(factSanitizer, "factSanitizer不能为空");
        this.associationService = Objects.requireNonNull(
                associationService, "associationService不能为空"
        );
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
    }

    /**
     * 复权后锁定来源快照和当前字段策略，再写入子快照；任一校验失败均整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public BusinessSnapshot derive(DeriveCommand command) {
        validate(command);
        BusinessSnapshotAccessService.AccessGrant grant = accessService.reauthorize(
                new BusinessSnapshotAccessService.AccessCommand(
                        command.agentRunId(), command.userId(), command.sessionId(),
                        command.authorization(), command.secureContext(), command.datasetCode(),
                        command.subjectType(), command.subjectId(), command.targetQuery()
                )
        ).orElseThrow(this::unavailable);
        LocalDateTime now = LocalDateTime.now(clock);
        BusinessSnapshot source = snapshotMapper.selectOne(
                Wrappers.<BusinessSnapshot>lambdaQuery()
                        .eq(BusinessSnapshot::getSnapshotId, command.sourceSnapshotId())
                        .eq(BusinessSnapshot::getUserId, command.userId())
                        .eq(BusinessSnapshot::getSessionId, command.sessionId())
                        .last("LIMIT 1 FOR UPDATE")
        );
        if (!sourceUsable(source, command, grant, now)) {
            throw unavailable();
        }
        List<ReportDatasetField> fields = fieldMapper.selectList(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .eq(ReportDatasetField::getDatasetId, grant.datasetId())
                        .orderByAsc(ReportDatasetField::getDisplayOrder, ReportDatasetField::getId)
        );
        rejectOversized(source.getQueryJson(), MAX_QUERY_BYTES);
        Map<String, Object> sourceQuery = readMap(source.getQueryJson(), "来源查询条件");
        if (!canDerive(
                sourceQuery, command.targetQuery(), command.requestedGrain(),
                command.requiredFactCodes(), fields
        )) {
            throw unavailable();
        }
        List<BusinessSnapshotItem> sourceItems = itemMapper.selectList(
                Wrappers.<BusinessSnapshotItem>lambdaQuery()
                        .eq(BusinessSnapshotItem::getSnapshotId, source.getSnapshotId())
                        .orderByAsc(BusinessSnapshotItem::getId)
                        .last("LIMIT " + (MAX_ITEMS + 1) + " FOR UPDATE")
        );
        if (!itemsUsable(sourceItems, command)) {
            throw unavailable();
        }

        Map<String, ReportDatasetField> fieldsByCode = fieldMap(fields);
        BusinessSnapshotMatcher.DateRange targetRange = BusinessSnapshotMatcher.DateRange
                .parse(command.targetQuery()).orElseThrow(this::unavailable);
        rejectOversized(source.getFactsJson(), MAX_FACT_BYTES);
        DerivationFacts derived = deriveFacts(
                readMap(source.getFactsJson(), "来源安全事实"),
                sourceItems, fieldsByCode, command.requiredFactCodes(), targetRange
        );
        String derivedFactsJson = writeJson(derived.facts(), "派生安全事实");
        if (derivedFactsJson.getBytes(StandardCharsets.UTF_8).length > MAX_FACT_BYTES) {
            throw unavailable();
        }
        LocalDateTime expiresAt = source.getExpiresAt();
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            throw unavailable();
        }
        BusinessSnapshot child = childSnapshot(
                source, command, derivedFactsJson, expiresAt, now
        );
        if (snapshotMapper.insert(child) != 1) {
            throw internal("派生快照写入失败");
        }
        for (BusinessSnapshotItem sourceItem : sourceItems) {
            BusinessSnapshotItem childItem = childItem(
                    child.getSnapshotId(), sourceItem,
                    derived.counts().getOrDefault(sourceItem.getItemKey(), 0), now
            );
            if (itemMapper.insert(childItem) != 1) {
                throw internal("派生快照执行项写入失败");
            }
        }
        return child;
    }

    /**
     * 从人员来源快照派生仅含项目直接关联记录的安全快照。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProjectAssociationDerivation deriveProjectAssociation(
            ProjectAssociationCommand command) {
        validateProjectAssociation(command);
        BusinessSnapshotAccessService.AccessGrant grant = accessService.reauthorize(
                new BusinessSnapshotAccessService.AccessCommand(
                        command.agentRunId(), command.userId(), command.sessionId(),
                        command.authorization(), command.secureContext(), command.datasetCode(),
                        BusinessSubjectType.PERSON, command.subjectId(), command.targetQuery()
                )
        ).orElseThrow(this::unavailable);
        LocalDateTime now = LocalDateTime.now(clock);
        BusinessSnapshot source = snapshotMapper.selectOne(
                Wrappers.<BusinessSnapshot>lambdaQuery()
                        .eq(BusinessSnapshot::getSnapshotId, command.sourceSnapshotId())
                        .eq(BusinessSnapshot::getUserId, command.userId())
                        .eq(BusinessSnapshot::getSessionId, command.sessionId())
                        .last("LIMIT 1 FOR UPDATE")
        );
        if (!projectSourceUsable(source, command, grant, now)) {
            throw unavailable();
        }
        List<BusinessSnapshotItem> sourceItems = itemMapper.selectList(
                Wrappers.<BusinessSnapshotItem>lambdaQuery()
                        .eq(BusinessSnapshotItem::getSnapshotId, source.getSnapshotId())
                        .orderByAsc(BusinessSnapshotItem::getId)
                        .last("LIMIT " + (MAX_ITEMS + 1) + " FOR UPDATE")
        );
        if (sourceItems == null || sourceItems.size() != 1
                || !projectSourceItemUsable(sourceItems.get(0))) {
            throw unavailable();
        }
        List<ReportDatasetField> fields = fieldMapper.selectList(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .eq(ReportDatasetField::getDatasetId, grant.datasetId())
                        .orderByAsc(ReportDatasetField::getDisplayOrder, ReportDatasetField::getId)
        );
        String factCode = associationFactCode(command.datasetType());
        ReportDatasetField field = fieldMap(fields).get(factCode);
        if (field == null || !Boolean.TRUE.equals(field.getCalculable())) {
            throw unavailable();
        }
        rejectOversized(source.getFactsJson(), MAX_FACT_BYTES);
        List<Map<String, Object>> records = projectRecords(
                readMap(source.getFactsJson(), "来源安全事实"),
                sourceItems.get(0).getItemKey(), factCode
        );
        ClassifiedRecords classified = classify(records, command.datasetType(), command.projectContext());
        BusinessFactSanitizer.SanitizedFacts sanitized = factSanitizer.sanitize(
                Map.of(factCode, classified.direct()), List.of(toPolicy(field))
        );
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("calculation", sanitized.calculationFacts());
        envelope.put("display", sanitized.displayFacts());
        envelope.put("export", sanitized.exportFacts());
        envelope.put("model", sanitized.modelFacts());
        String factsJson = writeJson(Map.of("direct", envelope), "项目关联派生事实");
        if (factsJson.getBytes(StandardCharsets.UTF_8).length > MAX_FACT_BYTES) {
            throw unavailable();
        }
        BusinessSnapshot child = projectAssociationSnapshot(source, command, factsJson, now);
        if (snapshotMapper.insert(child) != 1) {
            throw internal("项目关联派生快照写入失败");
        }
        insertAssociationItem(child.getSnapshotId(), sourceItems.get(0),
                "direct", AssociationType.DIRECT, classified.direct().size(), now);
        insertAssociationItem(child.getSnapshotId(), sourceItems.get(0),
                "context", AssociationType.PROJECT_PERSON_PERIOD, classified.contextCount(), now);
        insertAssociationItem(child.getSnapshotId(), sourceItems.get(0),
                "unknown", AssociationType.UNKNOWN, classified.unknownCount(), now);
        insertAssociationItem(child.getSnapshotId(), sourceItems.get(0),
                "unrelated", AssociationType.UNRELATED, classified.unrelatedCount(), now);
        AssociationSummary summary = new AssociationSummary(
                classified.direct().size(), classified.contextCount(),
                classified.unknownCount(), classified.unrelatedCount()
        );
        return new ProjectAssociationDerivation(
                child, sanitized.calculationFacts(), summary, associationLabels(summary)
        );
    }

    private void validateProjectAssociation(ProjectAssociationCommand command) {
        if (command == null || command.datasetType() == null || command.projectContext() == null
                || !Set.of(DatasetType.TRAVEL, DatasetType.PUNCH, DatasetType.REIMBURSEMENT)
                .contains(command.datasetType())
                || !StringUtils.hasText(command.agentRunId())
                || !StringUtils.hasText(command.userId())
                || !StringUtils.hasText(command.sessionId())
                || !StringUtils.hasText(command.authorization())
                || !StringUtils.hasText(command.datasetCode())
                || !StringUtils.hasText(command.subjectId())
                || !StringUtils.hasText(command.sourceSnapshotId())) {
            throw unavailable();
        }
        requireLength(command.userId(), 128);
        requireLength(command.sessionId(), 64);
        requireLength(command.subjectId(), 128);
        requireLength(command.datasetCode(), 128);
        requireLength(command.sourceSnapshotId(), 32);
        String queryJson = writeJson(command.targetQuery(), "目标查询条件");
        if (queryJson.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
            throw unavailable();
        }
    }

    private boolean projectSourceUsable(
            BusinessSnapshot source,
            ProjectAssociationCommand command,
            BusinessSnapshotAccessService.AccessGrant grant,
            LocalDateTime now) {
        if (source == null
                || !Objects.equals(source.getUserId(), command.userId())
                || !Objects.equals(source.getSessionId(), command.sessionId())
                || !Objects.equals(source.getSubjectType(), BusinessSubjectType.PERSON.name())
                || !Objects.equals(source.getSubjectId(), command.subjectId())
                || !Objects.equals(source.getDatasetCode(), command.datasetCode())
                || !Objects.equals(source.getConfigChecksum(), grant.configChecksum())
                || !Objects.equals(source.getFieldPolicyChecksum(), grant.fieldPolicyChecksum())
                || !Set.of("COMPLETE", "PARTIAL_SUCCESS").contains(source.getStatus())
                || source.getExpiresAt() == null
                || !source.getExpiresAt().isAfter(now)) {
            return false;
        }
        rejectOversized(source.getQueryJson(), MAX_QUERY_BYTES);
        return Objects.equals(
                ReportDatasetValidator.canonicalSafeValue(
                        readMap(source.getQueryJson(), "来源查询条件")
                ),
                ReportDatasetValidator.canonicalSafeValue(command.targetQuery())
        );
    }

    private boolean projectSourceItemUsable(BusinessSnapshotItem item) {
        return item != null
                && StringUtils.hasText(item.getItemKey())
                && item.getItemKey().length() <= 128
                && Set.of(
                BusinessSnapshotItemStatus.SUCCESS.name(),
                BusinessSnapshotItemStatus.NO_DATA.name()
        ).contains(item.getStatus())
                && Objects.equals(item.getAssociationType(), AssociationType.DIRECT.name());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> projectRecords(
            Map<String, Object> sourceFacts,
            String itemKey,
            String factCode) {
        if (sourceFacts.size() != 1
                || !(sourceFacts.get(itemKey) instanceof Map<?, ?> envelope)
                || !envelope.keySet().equals(CHANNELS)
                || !(envelope.get("calculation") instanceof Map<?, ?> calculation)
                || !calculation.containsKey(factCode)
                || !(calculation.get(factCode) instanceof List<?> values)) {
            throw unavailable();
        }
        List<Map<String, Object>> records = new ArrayList<>(values.size());
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> record)) {
                throw unavailable();
            }
            records.add((Map<String, Object>) record);
        }
        return List.copyOf(records);
    }

    private ClassifiedRecords classify(
            List<Map<String, Object>> records,
            DatasetType type,
            ProjectAssociationContext context) {
        List<ProjectRecordAssociationService.MembershipPeriod> memberships =
                context.membershipAvailable()
                        ? context.membershipPeriods().stream()
                        .map(value -> new ProjectRecordAssociationService.MembershipPeriod(
                                value.start(), value.end()
                        )).toList()
                        : List.of();
        ProjectRecordAssociationService.ProjectIdentity project =
                new ProjectRecordAssociationService.ProjectIdentity(
                        context.projectCode(), context.projectId()
                );
        List<Map<String, Object>> direct = new ArrayList<>();
        int contextual = 0;
        int unknown = 0;
        int unrelated = 0;
        for (int index = 0; index < records.size(); index++) {
            Map<String, Object> record = records.get(index);
            AssociationType association = associationService.classify(
                    project, context.periodStart(), context.periodEnd(), memberships,
                    new ProjectRecordAssociationService.RecordReference(
                            recordId(record, index), textValue(record.get("projectCode")),
                            textValue(record.get("projectId")),
                            PersonBusinessFactAggregator.occurredOn(type, record)
                    )
            );
            switch (association) {
                case DIRECT -> direct.add(record);
                case PROJECT_PERSON_PERIOD -> contextual++;
                case UNKNOWN -> unknown++;
                case UNRELATED -> unrelated++;
            }
        }
        return new ClassifiedRecords(List.copyOf(direct), contextual, unknown, unrelated);
    }

    private String recordId(Map<String, Object> record, int index) {
        String value = textValue(record.get("recordId"));
        return StringUtils.hasText(value) ? value : "record-" + index;
    }

    private String textValue(Object value) {
        String text = value instanceof String string ? string.trim() : null;
        return StringUtils.hasText(text) ? text : null;
    }

    private String associationFactCode(DatasetType type) {
        return switch (type) {
            case TRAVEL -> PersonBusinessQueryService.TRAVEL_RECORDS;
            case PUNCH -> PersonBusinessQueryService.PUNCH_RECORDS;
            case REIMBURSEMENT -> PersonBusinessQueryService.REIMBURSEMENT_RECORDS;
            default -> throw unavailable();
        };
    }

    private BusinessSnapshot projectAssociationSnapshot(
            BusinessSnapshot source,
            ProjectAssociationCommand command,
            String factsJson,
            LocalDateTime now) {
        Map<String, Object> query = projectAssociationQuery(command);
        String queryJson = writeJson(query, "项目关联派生查询条件");
        if (queryJson.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
            throw unavailable();
        }
        BusinessSnapshot child = new BusinessSnapshot();
        child.setSnapshotId(UUID.randomUUID().toString().replace("-", ""));
        child.setUserId(command.userId());
        child.setSessionId(command.sessionId());
        child.setSubjectType(BusinessSubjectType.PERSON.name());
        child.setSubjectId(command.subjectId());
        child.setDatasetCode(command.datasetCode());
        child.setQueryJson(queryJson);
        child.setQueryHash(ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(query)
        ));
        child.setStatus(source.getStatus());
        child.setDataComplete(source.getDataComplete());
        child.setFactsJson(factsJson);
        child.setConfigChecksum(source.getConfigChecksum());
        child.setFieldPolicyChecksum(source.getFieldPolicyChecksum());
        child.setSourceSnapshotId(source.getSnapshotId());
        child.setExpiresAt(source.getExpiresAt());
        child.setCreatedAt(now);
        // 派生操作不能延长来源事实的新鲜期。
        child.setCompletedAt(source.getCompletedAt());
        return child;
    }

    private Map<String, Object> projectAssociationQuery(ProjectAssociationCommand command) {
        Map<String, Object> query = new LinkedHashMap<>(command.targetQuery());
        ProjectAssociationContext context = command.projectContext();
        putOrRemove(query, "projectCode", context.projectCode());
        putOrRemove(query, "projectId", context.projectId());
        query.put("periodStart", context.periodStart().toString());
        query.put("periodEnd", context.periodEnd().toString());
        return query;
    }

    private void putOrRemove(Map<String, Object> query, String key, String value) {
        if (StringUtils.hasText(value)) {
            query.put(key, value.trim());
        } else {
            query.remove(key);
        }
    }

    private void insertAssociationItem(
            String snapshotId,
            BusinessSnapshotItem source,
            String itemKey,
            AssociationType association,
            int count,
            LocalDateTime now) {
        BusinessSnapshotItem item = childItem(snapshotId, source, count, now);
        item.setItemKey(itemKey);
        item.setAssociationType(association.name());
        if (itemMapper.insert(item) != 1) {
            throw internal("项目关联派生快照执行项写入失败");
        }
    }

    private List<String> associationLabels(AssociationSummary summary) {
        List<String> labels = new ArrayList<>(2);
        if (summary.contextCount() > 0) {
            labels.add("存在项目人员期间关联记录，仅供上下文参考，不计入项目直接统计");
        }
        if (summary.unknownCount() > 0) {
            labels.add("部分记录的项目关联无法确认，未计入项目直接统计");
        }
        return List.copyOf(labels);
    }

    private void validate(DeriveCommand command) {
        if (command == null || command.subjectType() == null
                || command.associationType() == null
                || !StringUtils.hasText(command.agentRunId())
                || !StringUtils.hasText(command.userId())
                || !StringUtils.hasText(command.sessionId())
                || !StringUtils.hasText(command.authorization())
                || !StringUtils.hasText(command.subjectId())
                || !StringUtils.hasText(command.datasetCode())
                || !StringUtils.hasText(command.sourceSnapshotId())
                || command.requiredFactCodes().isEmpty()
                || command.requiredFactCodes().size() > MAX_ITEMS) {
            throw unavailable();
        }
        requireLength(command.userId(), 128);
        requireLength(command.sessionId(), 64);
        requireLength(command.subjectId(), 128);
        requireLength(command.datasetCode(), 128);
        requireLength(command.sourceSnapshotId(), 32);
        for (String factCode : command.requiredFactCodes()) {
            requireLength(factCode, 128);
        }
        String queryJson = writeJson(command.targetQuery(), "目标查询条件");
        if (queryJson.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
            throw unavailable();
        }
    }

    private void requireLength(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() > maxLength) {
            throw unavailable();
        }
    }

    private void rejectOversized(String value, int maxBytes) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw unavailable();
        }
    }

    private boolean sourceUsable(
            BusinessSnapshot source,
            DeriveCommand command,
            BusinessSnapshotAccessService.AccessGrant grant,
            LocalDateTime now) {
        return source != null
                && Objects.equals(source.getUserId(), command.userId())
                && Objects.equals(source.getSessionId(), command.sessionId())
                && Objects.equals(source.getSubjectType(), command.subjectType().name())
                && Objects.equals(source.getSubjectId(), command.subjectId())
                && Objects.equals(source.getDatasetCode(), command.datasetCode())
                && Objects.equals(source.getConfigChecksum(), grant.configChecksum())
                && Objects.equals(source.getFieldPolicyChecksum(), grant.fieldPolicyChecksum())
                && Set.of("COMPLETE", "PARTIAL_SUCCESS").contains(source.getStatus())
                && source.getExpiresAt() != null
                && source.getExpiresAt().isAfter(now);
    }

    private boolean canDerive(
            Map<String, Object> sourceQuery,
            Map<String, Object> targetQuery,
            String requestedGrain,
            Set<String> requiredFactCodes,
            List<ReportDatasetField> fields) {
        BusinessSnapshotMatcher.DateRange source = BusinessSnapshotMatcher.DateRange
                .parse(sourceQuery).orElse(null);
        BusinessSnapshotMatcher.DateRange target = BusinessSnapshotMatcher.DateRange
                .parse(targetQuery).orElse(null);
        BusinessSnapshotMatcher.TimeGrain grain = BusinessSnapshotMatcher.TimeGrain
                .parse(requestedGrain).orElse(null);
        BusinessSnapshotMatcher.TimeGrain canonicalGrain = BusinessSnapshotMatcher.TimeGrain
                .parse(Objects.toString(targetQuery.get("grain"), null)).orElse(null);
        if (source == null || target == null || grain == null
                || canonicalGrain != grain || !source.strictlyCovers(target)) {
            return false;
        }
        Map<String, Object> sourceOther = nonTemporal(sourceQuery);
        Map<String, Object> targetOther = nonTemporal(targetQuery);
        if (!Objects.equals(
                ReportDatasetValidator.canonicalSafeValue(sourceOther),
                ReportDatasetValidator.canonicalSafeValue(targetOther))) {
            return false;
        }
        Map<String, ReportDatasetField> byCode = fieldMap(fields);
        for (String code : requiredFactCodes) {
            ReportDatasetField field = byCode.get(code);
            if (field == null
                    || !Boolean.TRUE.equals(field.getCalculable())
                    || !Boolean.TRUE.equals(field.getFilterable())
                    || !"DATE_RECORD_LIST".equals(field.getFactType())
                    || BusinessSnapshotMatcher.TimeGrain.parse(field.getGrain())
                    .map(actual -> actual.supports(grain)).orElse(false) == false) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> nonTemporal(Map<String, Object> query) {
        Map<String, Object> copy = new LinkedHashMap<>(query);
        Set.of("year", "quarter", "startDate", "endDate", "grain").forEach(copy::remove);
        return copy;
    }

    private boolean itemsUsable(
            List<BusinessSnapshotItem> items,
            DeriveCommand command) {
        if (items == null || items.isEmpty() || items.size() > MAX_ITEMS) {
            return false;
        }
        for (BusinessSnapshotItem item : items) {
            /* 大明细的日期语义未在当前制品结构中声明，禁止猜测分块字段后裁剪。 */
            if (item == null
                    || !StringUtils.hasText(item.getItemKey())
                    || item.getItemKey().length() > 128
                    || StringUtils.hasText(item.getResultArtifactId())
                    || !Set.of(
                    BusinessSnapshotItemStatus.SUCCESS.name(),
                    BusinessSnapshotItemStatus.NO_DATA.name()
            ).contains(item.getStatus())
                    || !Objects.equals(item.getAssociationType(), command.associationType().name())) {
                return false;
            }
        }
        return true;
    }

    private DerivationFacts deriveFacts(
            Map<String, Object> sourceFacts,
            List<BusinessSnapshotItem> items,
            Map<String, ReportDatasetField> fields,
            Set<String> requiredFactCodes,
            BusinessSnapshotMatcher.DateRange range) {
        Set<String> itemKeys = items.stream().map(BusinessSnapshotItem::getItemKey).collect(
                java.util.stream.Collectors.toUnmodifiableSet()
        );
        if (!sourceFacts.keySet().equals(itemKeys)) {
            throw unavailable();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String itemKey : itemKeys) {
            Object envelopeValue = sourceFacts.get(itemKey);
            if (!(envelopeValue instanceof Map<?, ?> envelope)
                    || !envelope.keySet().equals(CHANNELS)) {
                throw unavailable();
            }
            Map<String, Object> derivedEnvelope = new LinkedHashMap<>();
            Object calculationValue = envelope.get("calculation");
            if (!(calculationValue instanceof Map<?, ?> calculationFacts)) {
                throw unavailable();
            }
            Map<String, Object> derivedStandardFacts = new LinkedHashMap<>();
            int calculationCount = -1;
            for (String factCode : requiredFactCodes) {
                ReportDatasetField field = fields.get(factCode);
                if (field == null || !Boolean.TRUE.equals(field.getCalculable())
                        || !calculationFacts.containsKey(factCode)) {
                    throw unavailable();
                }
                DerivedValue value = deriveValue(calculationFacts.get(factCode), range);
                derivedStandardFacts.put(factCode, value.value());
                calculationCount = calculationCount < 0
                        ? value.count()
                        : Math.min(calculationCount, value.count());
            }
            List<FieldPolicy> policies = requiredFactCodes.stream()
                    .map(fields::get)
                    .map(this::toPolicy)
                    .toList();
            BusinessFactSanitizer.SanitizedFacts sanitized = factSanitizer.sanitize(
                    derivedStandardFacts, policies
            );
            derivedEnvelope.put("calculation", sanitized.calculationFacts());
            derivedEnvelope.put("display", sanitized.displayFacts());
            derivedEnvelope.put("export", sanitized.exportFacts());
            derivedEnvelope.put("model", sanitized.modelFacts());
            result.put(itemKey, derivedEnvelope);
            counts.put(itemKey, Math.max(calculationCount, 0));
        }
        return new DerivationFacts(result, counts);
    }

    private FieldPolicy toPolicy(ReportDatasetField field) {
        if (field == null
                || field.getCalculable() == null
                || field.getDisplayable() == null
                || field.getExportable() == null
                || field.getModelVisible() == null
                || !StringUtils.hasText(field.getFactType())
                || !StringUtils.hasText(field.getMaskStrategy())
                || !StringUtils.hasText(field.getGrain())) {
            throw unavailable();
        }
        return new FieldPolicy(
                field.getFactCode(), field.getFactType(), field.getCalculable(),
                field.getDisplayable(), field.getExportable(), field.getModelVisible(),
                field.getMaskStrategy(), field.getGrain()
        );
    }

    private DerivedValue deriveValue(
            Object source,
            BusinessSnapshotMatcher.DateRange range) {
        /* 当前最小可证明契约仅允许纯日期记录列表；任何预聚合对象都必须重新查询。 */
        if (!(source instanceof List<?> records)) {
            throw unavailable();
        }
        List<Map<String, Object>> filtered = filterRecords(records, range);
        return new DerivedValue(filtered, filtered.size());
    }

    private List<Map<String, Object>> filterRecords(
            List<?> records,
            BusinessSnapshotMatcher.DateRange range) {
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Object value : records) {
            if (!(value instanceof Map<?, ?> record)) {
                throw unavailable();
            }
            Object dateValue = record.get("date");
            if (!StringUtils.hasText(Objects.toString(dateValue, ""))) {
                throw unavailable();
            }
            LocalDate date;
            try {
                date = LocalDate.parse(Objects.toString(dateValue));
            } catch (RuntimeException exception) {
                throw unavailable();
            }
            if (!date.isBefore(range.start()) && !date.isAfter(range.end())) {
                Map<String, Object> safeRecord = new LinkedHashMap<>();
                record.forEach((key, item) -> safeRecord.put(Objects.toString(key, ""), item));
                filtered.add(safeRecord);
            }
        }
        return List.copyOf(filtered);
    }

    private BusinessSnapshot childSnapshot(
            BusinessSnapshot source,
            DeriveCommand command,
            String factsJson,
            LocalDateTime expiresAt,
            LocalDateTime now) {
        BusinessSnapshot child = new BusinessSnapshot();
        child.setSnapshotId(UUID.randomUUID().toString().replace("-", ""));
        child.setUserId(command.userId());
        child.setSessionId(command.sessionId());
        child.setSubjectType(command.subjectType().name());
        child.setSubjectId(command.subjectId());
        child.setDatasetCode(command.datasetCode());
        child.setQueryJson(writeJson(command.targetQuery(), "目标查询条件"));
        child.setQueryHash(ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(command.targetQuery())
        ));
        child.setStatus(source.getStatus());
        child.setDataComplete(source.getDataComplete());
        child.setFactsJson(factsJson);
        child.setConfigChecksum(source.getConfigChecksum());
        child.setFieldPolicyChecksum(source.getFieldPolicyChecksum());
        child.setSourceSnapshotId(source.getSnapshotId());
        child.setExpiresAt(expiresAt);
        child.setCreatedAt(now);
        // 派生操作不能延长来源事实的新鲜期。
        child.setCompletedAt(source.getCompletedAt());
        return child;
    }

    private BusinessSnapshotItem childItem(
            String snapshotId,
            BusinessSnapshotItem source,
            int count,
            LocalDateTime now) {
        BusinessSnapshotItem item = new BusinessSnapshotItem();
        item.setSnapshotId(snapshotId);
        item.setItemKey(source.getItemKey());
        item.setWorkflowCode(source.getWorkflowCode());
        item.setWorkflowVersionId(source.getWorkflowVersionId());
        item.setWorkflowVersionNo(source.getWorkflowVersionNo());
        item.setWorkflowConfigChecksum(source.getWorkflowConfigChecksum());
        item.setWorkflowRunId(source.getWorkflowRunId());
        /* 子快照不引用含更宽范围的大明细制品，避免后续读取扩大范围。 */
        item.setResultArtifactId(null);
        item.setStatus(source.getStatus());
        item.setAssociationType(source.getAssociationType());
        item.setTotalCount(count);
        item.setSuccessCount(count);
        item.setFailureCount(0);
        item.setCreatedAt(now);
        return item;
    }

    private Map<String, ReportDatasetField> fieldMap(List<ReportDatasetField> fields) {
        Map<String, ReportDatasetField> result = new LinkedHashMap<>();
        if (fields != null) {
            for (ReportDatasetField field : fields) {
                if (field == null || !StringUtils.hasText(field.getFactCode())
                        || result.putIfAbsent(field.getFactCode(), field) != null) {
                    throw unavailable();
                }
            }
        }
        return result;
    }

    private Map<String, Object> readMap(String json, String name) {
        try {
            Map<String, Object> value = objectMapper.readerFor(new TypeReference<Map<String, Object>>() { })
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(json);
            return value == null ? Map.of() : value;
        } catch (Exception exception) {
            throw badRequest(name + "不可用");
        }
    }

    private String writeJson(Object value, String name) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw internal(name + "序列化失败");
        }
    }

    private BusinessException unavailable() {
        return badRequest(BusinessSnapshotMatcher.GENERIC_REQUERY_REASON);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private BusinessException internal(String message) {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, message);
    }

    public record DeriveCommand(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            BusinessSubjectType subjectType,
            String subjectId,
            String datasetCode,
            String sourceSnapshotId,
            Map<String, Object> targetQuery,
            AssociationType associationType,
            String requestedGrain,
            Set<String> requiredFactCodes) {

        @SuppressWarnings("unchecked")
        public DeriveCommand {
            secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    secureContext == null ? Map.of() : secureContext
            );
            targetQuery = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    targetQuery == null ? Map.of() : targetQuery
            );
            requiredFactCodes = requiredFactCodes == null
                    ? Set.of()
                    : Set.copyOf(requiredFactCodes);
        }

        /** 禁止认证、角色上下文和查询值进入日志。 */
        @Override
        public String toString() {
            return "DeriveCommand[userId=" + userId
                    + ", sessionId=" + sessionId
                    + ", datasetCode=" + datasetCode
                    + ", subjectType=" + subjectType
                    + ", sourceSnapshotPresent=" + StringUtils.hasText(sourceSnapshotId)
                    + ", authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", secureContextSize=" + secureContext.size()
                    + ", targetQuerySize=" + targetQuery.size() + ']';
        }
    }

    /** 项目关联派生只接收安全定位信息，不接收调用方提供的业务事实。 */
    public record ProjectAssociationCommand(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            String datasetCode,
            String subjectId,
            String sourceSnapshotId,
            Map<String, Object> targetQuery,
            DatasetType datasetType,
            ProjectAssociationContext projectContext) {

        @SuppressWarnings("unchecked")
        public ProjectAssociationCommand {
            secureContext = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    secureContext == null ? Map.of() : secureContext
            );
            targetQuery = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    targetQuery == null ? Map.of() : targetQuery
            );
        }

        @Override
        public String toString() {
            return "ProjectAssociationCommand[userId=" + userId
                    + ", sessionId=" + sessionId
                    + ", datasetCode=" + datasetCode
                    + ", datasetType=" + datasetType
                    + ", sourceSnapshotPresent=" + StringUtils.hasText(sourceSnapshotId)
                    + ", authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", secureContextSize=" + secureContext.size()
                    + ", targetQuerySize=" + targetQuery.size() + ']';
        }
    }

    /** 项目直接事实与快照引用来自同一次派生，回答和报告共享同一口径。 */
    public record ProjectAssociationDerivation(
            BusinessSnapshot snapshot,
            Map<String, Object> directCalculationFacts,
            AssociationSummary summary,
            List<String> labels) {

        @SuppressWarnings("unchecked")
        public ProjectAssociationDerivation {
            directCalculationFacts = (Map<String, Object>) ReportDatasetValidator.freezeSafeValue(
                    directCalculationFacts == null ? Map.of() : directCalculationFacts
            );
            labels = labels == null ? List.of() : List.copyOf(labels);
        }
    }

    private record DerivedValue(Object value, int count) {
    }

    private record DerivationFacts(
            Map<String, Object> facts,
            Map<String, Integer> counts) {
    }

    private record ClassifiedRecords(
            List<Map<String, Object>> direct,
            int contextCount,
            int unknownCount,
            int unrelatedCount) {
    }
}
