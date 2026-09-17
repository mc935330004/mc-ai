package org.example.ai.agent.business.snapshot;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.panorama.ProjectPanoramaSnapshotReuseService;
import org.example.ai.agent.business.person.PersonSnapshotReuseService;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessSnapshotReferenceValidationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-09T08:00:00Z"), ZoneOffset.UTC
    );
    private static final String CONFIG_CHECKSUM = "c".repeat(64);
    private static final String FIELD_CHECKSUM = "d".repeat(64);

    @Test
    void authorizationEntryPointsMustNotDeclareReadOnlyOuterTransactions() throws Exception {
        assertThat(BusinessSnapshotReferenceValidationService.class
                .getDeclaredMethod(
                        "validate",
                        BusinessSnapshotReferenceValidationService.ValidationCommand.class
                )
                .getAnnotation(Transactional.class)).isNull();
        assertThat(ProjectPanoramaSnapshotReuseService.class
                .getDeclaredMethod(
                        "reuse", ProjectPanoramaSnapshotReuseService.ReuseCommand.class
                )
                .getAnnotation(Transactional.class)).isNull();
        assertThat(PersonSnapshotReuseService.class
                .getDeclaredMethod("reuse", BusinessSnapshotMatcher.MatchCommand.class)
                .getAnnotation(Transactional.class)).isNull();
    }

    @Test
    void exactReferenceShouldReauthorizeCurrentDatasetAndValidateAllBindings() {
        Fixture fixture = new Fixture();
        when(fixture.snapshotMapper.selectById("snapshot-1"))
                .thenReturn(snapshot("user-1", "session-1", "raw-project-1",
                        LocalDateTime.of(2026, 9, 9, 9, 0)));
        when(fixture.accessService.reauthorize(any())).thenReturn(Optional.of(
                new BusinessSnapshotAccessService.AccessGrant(
                        1L, CONFIG_CHECKSUM, FIELD_CHECKSUM, 60
                )
        ));

        boolean valid = fixture.service.validate(command());

        assertThat(valid).isTrue();
        ArgumentCaptor<BusinessSnapshotAccessService.AccessCommand> access =
                ArgumentCaptor.forClass(BusinessSnapshotAccessService.AccessCommand.class);
        verify(fixture.accessService).reauthorize(access.capture());
        assertThat(access.getValue().canonicalQuery())
                .containsExactlyEntriesOf(Map.of("projectCode", "P-1001"));
        assertThat(access.getValue().subjectId()).isEqualTo("raw-project-1");
    }

    @Test
    void ownershipOrSubjectMismatchMustRejectBeforeCurrentAccessCheck() {
        Fixture fixture = new Fixture();
        when(fixture.snapshotMapper.selectById("snapshot-1"))
                .thenReturn(snapshot("another-user", "session-1", "raw-project-1",
                        LocalDateTime.of(2026, 9, 9, 9, 0)));

        assertThat(fixture.service.validate(command())).isFalse();
        verify(fixture.accessService, never()).reauthorize(any());
    }

    @Test
    void queryHashMismatchMustReject() {
        Fixture fixture = new Fixture();
        BusinessSnapshot snapshot = snapshot(
                "user-1", "session-1", "raw-project-1",
                LocalDateTime.of(2026, 9, 9, 9, 0)
        );
        snapshot.setQueryHash("e".repeat(64));
        when(fixture.snapshotMapper.selectById("snapshot-1")).thenReturn(snapshot);
        when(fixture.accessService.reauthorize(any())).thenReturn(Optional.of(
                new BusinessSnapshotAccessService.AccessGrant(
                        1L, CONFIG_CHECKSUM, FIELD_CHECKSUM, 60
                )
        ));

        assertThat(fixture.service.validate(command())).isFalse();
    }

    @Test
    void currentDatasetConfigOrFieldPolicyMismatchMustReject() {
        Fixture fixture = new Fixture();
        when(fixture.snapshotMapper.selectById("snapshot-1"))
                .thenReturn(snapshot("user-1", "session-1", "raw-project-1",
                        LocalDateTime.of(2026, 9, 9, 9, 0)));
        when(fixture.accessService.reauthorize(any())).thenReturn(Optional.of(
                new BusinessSnapshotAccessService.AccessGrant(
                        1L, "f".repeat(64), "e".repeat(64), 60
                )
        ));

        assertThat(fixture.service.validate(command())).isFalse();
    }

    @Test
    void expiredOrRevokedReferenceMustReject() {
        Fixture fixture = new Fixture();
        when(fixture.snapshotMapper.selectById("snapshot-1"))
                .thenReturn(snapshot("user-1", "session-1", "raw-project-1",
                        LocalDateTime.of(2026, 9, 9, 7, 59)));
        when(fixture.accessService.reauthorize(any())).thenReturn(Optional.empty());

        assertThat(fixture.service.validate(command())).isFalse();
        verify(fixture.accessService, never()).reauthorize(any());
    }

    @Test
    void validationCommandToStringMustHideSnapshotAuthorizationSubjectAndQueryValues() {
        String value = command().toString();

        assertThat(value).doesNotContain(
                "snapshot-1", "Bearer secret", "raw-project-1", "P-1001"
        );
    }

    private static BusinessSnapshotReferenceValidationService.ValidationCommand command() {
        return new BusinessSnapshotReferenceValidationService.ValidationCommand(
                "run-1", "user-1", "session-1", "Bearer secret", Map.of("role", "PM"),
                BusinessSubjectType.PROJECT, "raw-project-1", "CONTRACT", "snapshot-1",
                FIELD_CHECKSUM, Map.of("projectCode", "P-1001")
        );
    }

    private static BusinessSnapshot snapshot(
            String userId,
            String sessionId,
            String subjectId,
            LocalDateTime expiresAt) {
        BusinessSnapshot snapshot = new BusinessSnapshot();
        snapshot.setSnapshotId("snapshot-1");
        snapshot.setUserId(userId);
        snapshot.setSessionId(sessionId);
        snapshot.setSubjectType("PROJECT");
        snapshot.setSubjectId(subjectId);
        snapshot.setDatasetCode("CONTRACT");
        snapshot.setQueryHash(ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(Map.of("projectCode", "P-1001"))
        ));
        snapshot.setConfigChecksum(CONFIG_CHECKSUM);
        snapshot.setFieldPolicyChecksum(FIELD_CHECKSUM);
        snapshot.setStatus("COMPLETE");
        snapshot.setExpiresAt(expiresAt);
        return snapshot;
    }

    private static final class Fixture {
        private final BusinessSnapshotMapper snapshotMapper = mock(BusinessSnapshotMapper.class);
        private final BusinessSnapshotAccessService accessService = mock(BusinessSnapshotAccessService.class);
        private final BusinessSnapshotReferenceValidationService service =
                new BusinessSnapshotReferenceValidationService(snapshotMapper, accessService, CLOCK);
    }
}
