package org.example.ai.agent.business.snapshot;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
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
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public BusinessSnapshotDerivationService(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ReportDatasetFieldMapper fieldMapper,
            BusinessSnapshotAccessService accessService,
            BusinessFactSanitizer factSanitizer,
            ObjectMapper objectMapper) {
        this(snapshotMapper, itemMapper, fieldMapper, accessService, factSanitizer,
                objectMapper, Clock.systemDefaultZone());
    }

    /** 测试构造器允许固定时钟。 */
    public BusinessSnapshotDerivationService(
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ReportDatasetFieldMapper fieldMapper,
            BusinessSnapshotAccessService accessService,
            BusinessFactSanitizer factSanitizer,
            ObjectMapper objectMapper,
            Clock clock) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper不能为空");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper不能为空");
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper不能为空");
        this.accessService = Objects.requireNonNull(accessService, "accessService不能为空");
        this.factSanitizer = Objects.requireNonNull(factSanitizer, "factSanitizer不能为空");
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
        child.setCompletedAt(now);
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
            Map<String, Object> value = objectMapper.readValue(json, new TypeReference<>() { });
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

    private record DerivedValue(Object value, int count) {
    }

    private record DerivationFacts(
            Map<String, Object> facts,
            Map<String, Integer> counts) {
    }
}
