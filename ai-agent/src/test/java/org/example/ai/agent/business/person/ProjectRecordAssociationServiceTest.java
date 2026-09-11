package org.example.ai.agent.business.person;

import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.person.ProjectRecordAssociationService.MembershipPeriod;
import org.example.ai.agent.business.person.ProjectRecordAssociationService.ProjectIdentity;
import org.example.ai.agent.business.person.ProjectRecordAssociationService.RecordReference;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class ProjectRecordAssociationServiceTest {

    private final ProjectRecordAssociationService service = new ProjectRecordAssociationService();

    @Test
    void matchingProjectIdentityIsDirect() {
        AssociationType type = service.classify(
                new ProjectIdentity("XXXT2674040", "P-1"),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                List.of(),
                new RecordReference("T-1", "XXXT2674040", "P-1", null)
        );

        assertThat(type).isEqualTo(AssociationType.DIRECT);
    }

    @Test
    void conflictingProjectIdentityIsUnrelatedAndNeverFallsBackToMembership() {
        AssociationType type = service.classify(
                new ProjectIdentity("XXXT2674040", "P-1"),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                List.of(new MembershipPeriod(LocalDate.of(2026, 1, 1), null)),
                new RecordReference("T-1", "OTHER", null, LocalDate.of(2026, 3, 1))
        );

        assertThat(type).isEqualTo(AssociationType.UNRELATED);
    }

    @Test
    void recordWithoutProjectIdentityWithinProjectAndMembershipPeriodsIsContextual() {
        AssociationType type = service.classify(
                new ProjectIdentity("XXXT2674040", "P-1"),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                List.of(new MembershipPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 6, 30))),
                new RecordReference("T-1", null, null, LocalDate.of(2026, 3, 1))
        );

        assertThat(type).isEqualTo(AssociationType.PROJECT_PERSON_PERIOD);
    }

    @Test
    void missingIdentityAndMembershipIsUnknownWithoutInventingTotals() {
        AssociationType type = service.classify(
                new ProjectIdentity("XXXT2674040", "P-1"),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                List.of(),
                new RecordReference("T-1", null, null, LocalDate.of(2026, 3, 1))
        );

        assertThat(type).isEqualTo(AssociationType.UNKNOWN);
    }

    @Test
    void missingDateOrPeriodMatchIsUnknown() {
        ProjectIdentity target = new ProjectIdentity("XXXT2674040", "P-1");
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        List<MembershipPeriod> memberships = List.of(new MembershipPeriod(start, end));

        assertThat(service.classify(target, start, end, memberships,
                new RecordReference("T-1", null, null, null)))
                .isEqualTo(AssociationType.UNKNOWN);
        assertThat(service.classify(target, start, end, memberships,
                new RecordReference("T-2", null, null, LocalDate.of(2027, 1, 1))))
                .isEqualTo(AssociationType.UNKNOWN);
    }

    @Test
    void invalidInputFailsClosed() {
        ProjectIdentity target = new ProjectIdentity("XXXT2674040", "P-1");
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 12, 31);
        RecordReference record = new RecordReference("T-1", null, null, start);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.classify(null, start, end, List.of(), record));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.classify(target, end, start, List.of(), record));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.classify(target, start, end, null, record));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.classify(target, start, end, List.of(), null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MembershipPeriod(null, end));
    }
}
