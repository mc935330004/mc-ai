package org.example.ai.agent.business.person;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.snapshot.BusinessSnapshotMatcher;
import org.example.ai.agent.business.snapshot.SnapshotMatchDecision;
import org.example.ai.agent.business.snapshot.SnapshotMatchResult;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 完成权限复核、快照二次读取与安全 calculation 解析。
 */
@Service
public class PersonSnapshotReuseService {

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonSnapshotReuseService.class);
    private static final String SNAPSHOT_ITEM_KEY = "person";
    private static final int MAX_FACT_BYTES = 256 * 1024;
    private static final Set<String> REUSABLE_STATUS = Set.of("COMPLETE", "PARTIAL_SUCCESS");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final BusinessSnapshotMatcher snapshotMatcher;
    private final BusinessSnapshotMapper snapshotMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public PersonSnapshotReuseService(
            BusinessSnapshotMatcher snapshotMatcher,
            BusinessSnapshotMapper snapshotMapper,
            ObjectMapper objectMapper) {
        this(snapshotMatcher, snapshotMapper, objectMapper, Clock.systemDefaultZone());
    }

    PersonSnapshotReuseService(
            BusinessSnapshotMatcher snapshotMatcher,
            BusinessSnapshotMapper snapshotMapper,
            ObjectMapper objectMapper,
            Clock clock) {
        this.snapshotMatcher = Objects.requireNonNull(snapshotMatcher, "snapshotMatcher不能为空");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
    }

    public Optional<ReuseResult> reuse(BusinessSnapshotMatcher.MatchCommand command) {
        SnapshotMatchResult match = snapshotMatcher.match(command);
        if (match == null
                || match.decision() != SnapshotMatchDecision.REUSE
                || !StringUtils.hasText(match.snapshotId())) {
            return Optional.empty();
        }
        BusinessSnapshot snapshot = snapshotMapper.selectById(match.snapshotId());
        if (!usable(snapshot, match.snapshotId(), command)) {
            return Optional.empty();
        }
        String expectedQueryHash = ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(command.canonicalQuery())
        );
        String factsJson = snapshot.getFactsJson();
        if (!Objects.equals(snapshot.getQueryHash(), expectedQueryHash)
                || !StringUtils.hasText(factsJson)
                || factsJson.getBytes(StandardCharsets.UTF_8).length > MAX_FACT_BYTES) {
            return Optional.empty();
        }
        Map<String, Object> calculation = calculation(factsJson, command.datasetCode());
        if (calculation == null
                || !calculation.keySet().containsAll(command.requiredFactCodes())) {
            return Optional.empty();
        }
        return Optional.of(new ReuseResult(
                snapshot.getSnapshotId(), snapshot.getFieldPolicyChecksum(),
                Boolean.TRUE.equals(snapshot.getDataComplete()), calculation
        ));
    }

    private boolean usable(
            BusinessSnapshot snapshot,
            String snapshotId,
            BusinessSnapshotMatcher.MatchCommand command) {
        return snapshot != null
                && Objects.equals(snapshot.getSnapshotId(), snapshotId)
                && Objects.equals(snapshot.getUserId(), command.userId())
                && Objects.equals(snapshot.getSessionId(), command.sessionId())
                && Objects.equals(snapshot.getSubjectType(), BusinessSubjectType.PERSON.name())
                && Objects.equals(snapshot.getSubjectId(), command.subjectId())
                && Objects.equals(snapshot.getDatasetCode(), command.datasetCode())
                && REUSABLE_STATUS.contains(snapshot.getStatus())
                && StringUtils.hasText(snapshot.getFieldPolicyChecksum())
                && snapshot.getExpiresAt() != null
                && snapshot.getExpiresAt().isAfter(LocalDateTime.now(clock));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> calculation(String factsJson, String datasetCode) {
        try {
            Map<String, Object> root = objectMapper.readerFor(MAP_TYPE)
                    .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readValue(factsJson);
            Object itemValue = root.get(SNAPSHOT_ITEM_KEY);
            if (!(itemValue instanceof Map<?, ?> item)
                    || !(item.get("calculation") instanceof Map<?, ?> calculation)) {
                LOGGER.warn(
                        "人员快照解析失败 datasetCode={} exceptionType={}",
                        datasetCode,
                        "ProtocolMismatch"
                );
                return null;
            }
            return (Map<String, Object>) calculation;
        } catch (Exception exception) {
            LOGGER.warn(
                    "人员快照解析失败 datasetCode={} exceptionType={}",
                    datasetCode,
                    exception.getClass().getSimpleName()
            );
            return null;
        }
    }

    /**
     * 复用结果只暴露已完成权限和策略校验的不透明引用，以及 calculation 安全事实。
     */
    public record ReuseResult(
            String snapshotId,
            String fieldPolicyChecksum,
            boolean dataComplete,
            Map<String, Object> calculation) {

        public ReuseResult {
            @SuppressWarnings("unchecked")
            Map<String, Object> frozen = (Map<String, Object>)
                    ReportDatasetValidator.freezeSafeValue(
                            calculation == null ? Map.of() : calculation
                    );
            calculation = frozen;
        }

        @Override
        public String toString() {
            return "ReuseResult[snapshotPresent=" + StringUtils.hasText(snapshotId)
                    + ", fieldPolicyPresent=" + StringUtils.hasText(fieldPolicyChecksum)
                    + ", dataComplete=" + dataComplete
                    + ", calculationFactCount=" + calculation.size() + ']';
        }
    }
}
