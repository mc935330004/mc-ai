package org.example.ai.agent.business.snapshot;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.snapshot.BusinessSnapshotAccessService.AccessGrant;
import org.example.ai.agent.business.snapshot.BusinessSnapshotMatcher.MatchCommand;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshotItem;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotItemMapper;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.workflow.answer.artifact.mapper.ResultArtifactMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class BusinessSnapshotMatcherTest {

    private static final String CONFIG = "a".repeat(64);
    private static final String POLICY = "b".repeat(64);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 2, 10, 0);

    private BusinessSnapshotMapper snapshotMapper;
    private BusinessSnapshotItemMapper itemMapper;
    private ReportDatasetFieldMapper fieldMapper;
    private ResultArtifactMapper artifactMapper;
    private BusinessSnapshotAccessService accessService;
    private BusinessSnapshotMatcher matcher;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "snapshot-test"),
                BusinessSnapshot.class
        );
        snapshotMapper = mock(BusinessSnapshotMapper.class);
        itemMapper = mock(BusinessSnapshotItemMapper.class);
        fieldMapper = mock(ReportDatasetFieldMapper.class);
        artifactMapper = mock(ResultArtifactMapper.class);
        accessService = mock(BusinessSnapshotAccessService.class);
        matcher = new BusinessSnapshotMatcher(
                snapshotMapper, itemMapper, fieldMapper, artifactMapper,
                accessService, new ObjectMapper(), fixedClock()
        );
        when(accessService.reauthorize(any())).thenReturn(Optional.of(
                new AccessGrant(9L, CONFIG, POLICY)
        ));
        when(fieldMapper.selectList(any())).thenReturn(List.of(field("DAY", true)));
        when(itemMapper.selectList(any())).thenReturn(List.of(item(AssociationType.DIRECT)));
    }

    @Test
    void shouldReuseAnExactAuthorizedSnapshot() {
        Map<String, Object> query = Map.of("year", 2026, "grain", "YEAR");
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot(
                "exact", "PROJECT", "P100", query, POLICY, NOW.plusHours(1)
        )));

        SnapshotMatchResult result = matcher.match(command(
                "P100", query, "YEAR", false
        ));

        assertThat(result.decision()).isEqualTo(SnapshotMatchDecision.REUSE);
        assertThat(result.snapshotId()).isEqualTo("exact");
        verify(accessService).reauthorize(any());
    }

    @Test
    void shouldReuseNoDataItemUsingPersistedStatusContract() {
        Map<String, Object> query = Map.of("year", 2026, "grain", "YEAR");
        BusinessSnapshotItem noData = item(AssociationType.DIRECT);
        noData.setStatus(BusinessSnapshotItemStatus.NO_DATA.name());
        when(itemMapper.selectList(any())).thenReturn(List.of(noData));
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot(
                "empty", "PROJECT", "P100", query, POLICY, NOW.plusHours(1)
        )));

        assertThat(matcher.match(command("P100", query, "YEAR", false)).decision())
                .isEqualTo(SnapshotMatchDecision.REUSE);
    }

    @Test
    void shouldBypassMatchingWhenRefreshWasExplicitlyRequested() {
        SnapshotMatchResult result = matcher.match(command(
                "P100", Map.of("year", 2026), "YEAR", true
        ));

        assertThat(result.decision()).isEqualTo(SnapshotMatchDecision.REQUERY);
        verify(snapshotMapper, never()).selectList(any());
        verify(accessService, never()).reauthorize(any());
    }

    @Test
    void shouldDeriveQuarterFromAnnualDailySnapshot() {
        Map<String, Object> annual = Map.of("year", 2026, "grain", "YEAR");
        Map<String, Object> quarter = Map.of(
                "startDate", "2026-01-01", "endDate", "2026-03-31", "grain", "QUARTER"
        );
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot(
                "annual", "PERSON", "E100", annual, POLICY, NOW.plusHours(1)
        )));
        assertThat(matcher.canDerive(
                annual, quarter, "QUARTER", Set.of("attendanceDetails"),
                List.of(field("DAY", true))
        )).isTrue();

        SnapshotMatchResult result = matcher.match(command(
                BusinessSubjectType.PERSON, "E100", quarter, "QUARTER", false
        ));

        assertThat(result.decision()).as(result.toString())
                .isEqualTo(SnapshotMatchDecision.DERIVE);
        assertThat(result.snapshotId()).isEqualTo("annual");
    }

    @Test
    void shouldRequeryWhenMonthlyFactsCannotProvideDailyDetail() {
        when(fieldMapper.selectList(any())).thenReturn(List.of(field("MONTH", true)));
        Map<String, Object> annual = Map.of("year", 2026, "grain", "YEAR");
        Map<String, Object> day = Map.of(
                "startDate", "2026-01-01", "endDate", "2026-01-01", "grain", "DAY"
        );
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot(
                "monthly", "PERSON", "E100", annual, POLICY, NOW.plusHours(1)
        )));
        assertThat(matcher.canDerive(
                annual, day, "DAY", Set.of("attendanceDetails"),
                List.of(field("MONTH", true))
        )).isFalse();

        assertThat(matcher.match(command(
                BusinessSubjectType.PERSON, "E100", day, "DAY", false
        )).decision()).as("monthly source must not produce daily facts")
                .isEqualTo(SnapshotMatchDecision.REQUERY);
    }

    @Test
    void shouldRejectDifferentSubjectExpiredAndOldPolicySnapshots() {
        Map<String, Object> query = Map.of("year", 2026);
        when(snapshotMapper.selectList(any())).thenReturn(List.of(
                snapshot("other", "PROJECT", "P200", query, POLICY, NOW.plusHours(1)),
                snapshot("expired", "PROJECT", "P100", query, POLICY, NOW.minusSeconds(1)),
                snapshot("policy", "PROJECT", "P100", query, "c".repeat(64), NOW.plusHours(1))
        ));

        assertThat(matcher.match(command("P100", query, "YEAR", false)).decision())
                .isEqualTo(SnapshotMatchDecision.REQUERY);
    }

    @Test
    void shouldFailClosedForMissingOrInvalidTimeConditions() {
        Map<String, Object> source = Map.of("keyword", "all");
        Map<String, Object> target = Map.of("keyword", "quarter");
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot(
                "source", "PROJECT", "P100", source, POLICY, NOW.plusHours(1)
        )));

        assertThat(matcher.match(command("P100", target, "QUARTER", false)).decision())
                .isEqualTo(SnapshotMatchDecision.REQUERY);
    }

    @Test
    void matchCommandMustNotPrintCredentialsOrQueryValues() {
        assertThat(command("P100", Map.of("secretFilter", "private"), "YEAR", false).toString())
                .doesNotContain("Bearer current", "employee", "private")
                .contains("authorizationPresent=true");
    }

    @Test
    void shouldNotDeriveAcrossDifferentNonTemporalFiltersOrUndeclaredFacts() {
        Map<String, Object> source = Map.of(
                "year", 2026, "grain", "YEAR", "status", "ACTIVE"
        );
        Map<String, Object> target = Map.of(
                "startDate", "2026-01-01", "endDate", "2026-03-31",
                "grain", "QUARTER", "status", "CLOSED"
        );
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot(
                "source", "PROJECT", "P100", source, POLICY, NOW.plusHours(1)
        )));
        assertThat(matcher.match(command("P100", target, "QUARTER", false)).decision())
                .isEqualTo(SnapshotMatchDecision.REQUERY);

        MatchCommand noFacts = new MatchCommand(
                "agent-1", "user-1", "session-1", "Bearer current", Map.of(),
                BusinessSubjectType.PROJECT, "P100", "ATTENDANCE",
                Map.of("startDate", "2026-01-01", "endDate", "2026-03-31",
                        "grain", "QUARTER", "status", "ACTIVE"),
                AssociationType.DIRECT, "QUARTER", Set.of(),
                Set.of(SnapshotFactChannel.CALCULATION), false
        );
        assertThat(matcher.match(noFacts).decision()).isEqualTo(SnapshotMatchDecision.REQUERY);
    }

    @Test
    void shouldRejectConflictingGrainAndMixedTimeConditions() {
        Map<String, Object> conflict = Map.of(
                "startDate", "2026-01-01", "endDate", "2026-03-31", "grain", "MONTH"
        );
        assertThat(matcher.match(command("P100", conflict, "DAY", false)).decision())
                .isEqualTo(SnapshotMatchDecision.REQUERY);

        Map<String, Object> mixed = Map.of(
                "year", 2026, "startDate", "2026-01-01",
                "endDate", "2026-03-31", "grain", "QUARTER"
        );
        when(snapshotMapper.selectList(any())).thenReturn(List.of(snapshot(
                "annual", "PROJECT", "P100",
                Map.of("year", 2026, "grain", "YEAR"), POLICY, NOW.plusHours(1)
        )));
        assertThat(matcher.match(command("P100", mixed, "QUARTER", false)).decision())
                .isEqualTo(SnapshotMatchDecision.REQUERY);
    }

    @Test
    void exactReuseMustContainEveryRequiredFactInRequestedChannel() {
        Map<String, Object> query = Map.of("year", 2026, "grain", "YEAR");
        BusinessSnapshot missing = snapshot(
                "missing", "PROJECT", "P100", query, POLICY, NOW.plusHours(1)
        );
        missing.setFactsJson(write(Map.of(
                "item-1", Map.of(
                        "calculation", Map.of(), "display", Map.of(),
                        "export", Map.of(), "model", Map.of()
                )
        )));
        when(snapshotMapper.selectList(any())).thenReturn(List.of(missing));

        assertThat(matcher.match(command("P100", query, "YEAR", false)).decision())
                .isEqualTo(SnapshotMatchDecision.REQUERY);
    }

    @Test
    void exactLookupMustUseQueryHashBeforeBoundedDerivationLookup() {
        Map<String, Object> query = Map.of("year", 2026, "grain", "YEAR");
        when(snapshotMapper.selectList(any())).thenReturn(List.of());

        matcher.match(command("P100", query, "YEAR", false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<BusinessSnapshot>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(snapshotMapper, times(2)).selectList(captor.capture());
        assertThat(captor.getAllValues().get(0).getCustomSqlSegment())
                .contains("query_hash", "LIMIT 50");
    }

    private MatchCommand command(String subjectId, Map<String, Object> query,
                                 String grain, boolean refresh) {
        return command(BusinessSubjectType.PROJECT, subjectId, query, grain, refresh);
    }

    private MatchCommand command(BusinessSubjectType type, String subjectId,
                                 Map<String, Object> query, String grain, boolean refresh) {
        return new MatchCommand(
                "agent-1", "user-1", "session-1", "Bearer current",
                Map.of("roles", List.of("employee")), type, subjectId,
                "ATTENDANCE", query, AssociationType.DIRECT, grain,
                Set.of("attendanceDetails"),
                Set.of(SnapshotFactChannel.CALCULATION), refresh
        );
    }

    private BusinessSnapshot snapshot(String id, String type, String subjectId,
                                      Map<String, Object> query, String policy,
                                      LocalDateTime expiresAt) {
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId(id);
        snapshot.setUserId("user-1");
        snapshot.setSessionId("session-1");
        snapshot.setSubjectType(type);
        snapshot.setSubjectId(subjectId);
        snapshot.setDatasetCode("ATTENDANCE");
        snapshot.setQueryJson(write(query));
        snapshot.setQueryHash(org.example.ai.agent.chat.support.ContentHashUtils.sha256(
                org.example.ai.agent.business.dataset.ReportDatasetValidator.canonicalSafeValue(query)
        ));
        snapshot.setStatus("COMPLETE");
        snapshot.setFactsJson(write(Map.of(
                "item-1", Map.of(
                        "calculation", Map.of("attendanceDetails", List.of()),
                        "display", Map.of("attendanceDetails", List.of()),
                        "export", Map.of("attendanceDetails", List.of()),
                        "model", Map.of("attendanceDetails", List.of())
                )
        )));
        snapshot.setConfigChecksum(CONFIG);
        snapshot.setFieldPolicyChecksum(policy);
        snapshot.setExpiresAt(expiresAt);
        return snapshot;
    }

    private BusinessSnapshotItem item(AssociationType associationType) {
        BusinessSnapshotItem item = new BusinessSnapshotItem();
        item.setSnapshotId("ignored");
        item.setItemKey("item-1");
        item.setAssociationType(associationType.name());
        item.setStatus("SUCCESS");
        return item;
    }

    private ReportDatasetField field(String grain, boolean filterable) {
        ReportDatasetField field = new ReportDatasetField();
        field.setFactCode("attendanceDetails");
        field.setFactType("DATE_RECORD_LIST");
        field.setCalculable(true);
        field.setFilterable(filterable);
        field.setGrain(grain);
        return field;
    }

    private String write(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Clock fixedClock() {
        return Clock.fixed(
                Instant.parse("2026-09-02T02:00:00Z"), ZoneId.of("Asia/Shanghai")
        );
    }
}
