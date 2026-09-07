package org.example.ai.agent.business.person;

import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.person.ProjectRecordAssociationService.BusinessRecord;
import org.example.ai.agent.business.person.ProjectRecordAssociationService.ProjectIdentity;
import org.example.ai.agent.business.person.ProjectRecordAssociationService.ProjectAssociationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectRecordAssociationServiceTest {

    private final ProjectRecordAssociationService service = new ProjectRecordAssociationService();

    @Test
    void directProjectCodeWinsAndOnlyDirectRecordsEnterProjectTotals() {
        BusinessRecord direct = record("direct", "P-100", null, null, null, "10", "8", "30", "20");
        BusinessRecord contextual = record(
                "context", null, null,
                LocalDate.of(2025, 12, 1), LocalDate.of(2026, 12, 31),
                "99", "99", "99", "99"
        );
        BusinessRecord otherProject = record(
                "other", "P-200", null,
                LocalDate.of(2025, 12, 1), LocalDate.of(2026, 12, 31),
                "50", "50", "50", "50"
        );

        ProjectAssociationResult result = service.associate(
                new ProjectIdentity("P-100", "ID-100"),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                List.of(direct, contextual, otherProject)
        );

        assertThat(result.directRecords()).extracting(item -> item.record().recordId())
                .containsExactly("direct");
        assertThat(result.directRecords()).allMatch(item -> item.type() == AssociationType.DIRECT);
        assertThat(result.contextRecords()).extracting(item -> item.record().recordId())
                .containsExactly("context");
        assertThat(result.contextRecords()).allMatch(
                item -> item.type() == AssociationType.PROJECT_PERSON_PERIOD
        );
        assertThat(result.contextLabel()).contains("不计入项目汇总");
        assertThat(result.totals().cost()).isEqualByComparingTo("10");
        assertThat(result.totals().hours()).isEqualByComparingTo("8");
        assertThat(result.totals().travelAmount()).isEqualByComparingTo("30");
        assertThat(result.totals().reimbursementAmount()).isEqualByComparingTo("20");
    }

    @Test
    void rosterMatchRequiresNoProjectIdentityAndOverlappingMembershipPeriod() {
        BusinessRecord before = new BusinessRecord(
                "before", null, null, LocalDate.of(2025, 12, 31),
                LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE
        );
        BusinessRecord membershipOutsidePeriod = new BusinessRecord(
                "outside-membership", null, null, LocalDate.of(2026, 6, 1),
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE
        );

        ProjectAssociationResult result = service.associate(
                new ProjectIdentity("P-100", null),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                List.of(before, membershipOutsidePeriod)
        );

        assertThat(result.directRecords()).isEmpty();
        assertThat(result.contextRecords()).isEmpty();
        assertThat(result.unrelatedRecords()).extracting(BusinessRecord::recordId)
                .containsExactly("before", "outside-membership");
    }

    @Test
    void projectIdCanAssociateDirectlyAndConflictingCodeIdFailClosed() {
        BusinessRecord byId = record(
                "by-id", null, "ID-100", null, null,
                "1", "1", "1", "1"
        );
        BusinessRecord conflict = record(
                "conflict", "P-100", "ID-OTHER", null, null,
                "9", "9", "9", "9"
        );

        ProjectAssociationResult result = service.associate(
                new ProjectIdentity(null, "ID-100"),
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                List.of(byId, conflict)
        );

        assertThat(result.directRecords()).extracting(item -> item.record().recordId())
                .containsExactly("by-id");
        assertThat(result.unrelatedRecords()).extracting(BusinessRecord::recordId)
                .containsExactly("conflict");
    }

    private BusinessRecord record(
            String id,
            String projectCode,
            String projectId,
            LocalDate rosterStart,
            LocalDate rosterEnd,
            String cost,
            String hours,
            String travel,
            String reimbursement) {
        return new BusinessRecord(
                id,
                projectCode,
                projectId,
                LocalDate.of(2026, 6, 1),
                rosterStart,
                rosterEnd,
                new BigDecimal(cost),
                new BigDecimal(hours),
                new BigDecimal(travel),
                new BigDecimal(reimbursement)
        );
    }
}
