package org.example.ai.agent.business.person;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.snapshot.BusinessSnapshotMatcher;
import org.example.ai.agent.business.snapshot.SnapshotFactChannel;
import org.example.ai.agent.business.snapshot.SnapshotMatchDecision;
import org.example.ai.agent.business.snapshot.SnapshotMatchResult;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersonSnapshotReuseServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 3, 12, 0);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-03T04:00:00Z"), ZoneId.of("Asia/Shanghai")
    );
    private static final String FACT_CODE = PersonBusinessQueryService.TRAVEL_RECORDS;

    @Mock
    private BusinessSnapshotMatcher snapshotMatcher;
    @Mock
    private BusinessSnapshotMapper snapshotMapper;

    private ObjectMapper objectMapper;
    private PersonSnapshotReuseService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new PersonSnapshotReuseService(
                snapshotMatcher, snapshotMapper, objectMapper, CLOCK
        );
    }

    @Test
    void queryHashMismatchRejectsReuse() throws Exception {
        arrangeReuse();
        BusinessSnapshot snapshot = validSnapshot();
        snapshot.setQueryHash("f".repeat(64));
        when(snapshotMapper.selectById("snapshot-1")).thenReturn(snapshot);

        Optional<PersonSnapshotReuseService.ReuseResult> result = service.reuse(command());

        assertThat(result).isEmpty();
    }

    @Test
    void oversizedUtf8FactsRejectReuse() throws Exception {
        arrangeReuse();
        BusinessSnapshot snapshot = validSnapshot();
        String oversized = "{\"person\":{\"calculation\":{\"" + FACT_CODE
                + "\":[]}},\"padding\":\"" + "测".repeat(90_000) + "\"}";
        assertThat(oversized.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(256 * 1024);
        snapshot.setFactsJson(oversized);
        when(snapshotMapper.selectById("snapshot-1")).thenReturn(snapshot);

        Optional<PersonSnapshotReuseService.ReuseResult> result = service.reuse(command());

        assertThat(result).isEmpty();
    }

    @Test
    void validSnapshotReturnsOnlyRequiredCalculationFacts() throws Exception {
        arrangeReuse();
        when(snapshotMapper.selectById("snapshot-1")).thenReturn(validSnapshot());

        Optional<PersonSnapshotReuseService.ReuseResult> result = service.reuse(command());

        assertThat(result).isPresent();
        PersonSnapshotReuseService.ReuseResult reused = result.orElseThrow();
        assertThat(reused.snapshotId()).isEqualTo("snapshot-1");
        assertThat(reused.fieldPolicyChecksum()).isEqualTo("a".repeat(64));
        assertThat(reused.dataComplete()).isTrue();
        assertThat(reused.calculation())
                .containsOnlyKeys(FACT_CODE)
                .containsEntry(FACT_CODE, java.util.List.of());
    }

    @Test
    void reuseResultToStringDoesNotExposeReferenceOrFactValues() throws Exception {
        arrangeReuse();
        BusinessSnapshot snapshot = validSnapshot();
        snapshot.setFactsJson(objectMapper.writeValueAsString(Map.of(
                "person", Map.of(
                        "calculation", Map.of(FACT_CODE, java.util.List.of("sensitive-fact"))
                )
        )));
        when(snapshotMapper.selectById("snapshot-1")).thenReturn(snapshot);

        String resultText = service.reuse(command()).orElseThrow().toString();

        assertThat(resultText)
                .contains("snapshotPresent=true", "calculationFactCount=1")
                .doesNotContain("snapshot-1", "a".repeat(64), "sensitive-fact", "E1001");
    }

    @Test
    void reuseMustNotWrapAuthorizationWorkflowInReadOnlyTransaction() throws Exception {
        Transactional transactional = PersonSnapshotReuseService.class
                .getMethod("reuse", BusinessSnapshotMatcher.MatchCommand.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNull();
    }

    private void arrangeReuse() {
        when(snapshotMatcher.match(any())).thenReturn(new SnapshotMatchResult(
                SnapshotMatchDecision.REUSE, "snapshot-1", "查询条件完全一致"
        ));
    }

    private BusinessSnapshot validSnapshot() throws Exception {
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId("snapshot-1");
        snapshot.setUserId("user-1");
        snapshot.setSessionId("session-1");
        snapshot.setSubjectType(BusinessSubjectType.PERSON.name());
        snapshot.setSubjectId("E1001");
        snapshot.setDatasetCode("PERSON_TRAVEL");
        snapshot.setQueryHash(ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(command().canonicalQuery())
        ));
        snapshot.setStatus("COMPLETE");
        snapshot.setDataComplete(true);
        snapshot.setFieldPolicyChecksum("a".repeat(64));
        snapshot.setExpiresAt(NOW.plusHours(1));
        snapshot.setFactsJson(objectMapper.writeValueAsString(Map.of(
                "person", Map.of(
                        "calculation", Map.of(FACT_CODE, java.util.List.of()),
                        "model", Map.of("must_not_escape", "secret")
                )
        )));
        return snapshot;
    }

    private BusinessSnapshotMatcher.MatchCommand command() {
        return new BusinessSnapshotMatcher.MatchCommand(
                "agent-run-1",
                "user-1",
                "session-1",
                "Bearer secret",
                Map.of("tenant", "tenant-secret"),
                BusinessSubjectType.PERSON,
                "E1001",
                "PERSON_TRAVEL",
                Map.of(
                        "employeeNo", "E1001",
                        "startDate", "2026-08-01",
                        "endDate", "2026-08-31"
                ),
                AssociationType.DIRECT,
                "DAY",
                Set.of(FACT_CODE),
                Set.of(SnapshotFactChannel.CALCULATION),
                false
        );
    }
}
