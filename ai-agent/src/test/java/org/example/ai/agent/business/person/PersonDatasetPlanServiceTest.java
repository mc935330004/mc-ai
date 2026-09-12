package org.example.ai.agent.business.person;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetPlan;
import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonDatasetPlanServiceTest {

    private final PersonDatasetSelectionService selectionService = new PersonDatasetSelectionService();
    private final PersonDatasetPlanService service = new PersonDatasetPlanService(new ObjectMapper());

    @Test
    void missingReimbursementConfigurationShouldReturnUnavailableSemantic() {
        PersonDatasetPlanService.PlanResult result = service.plan(
                selectionService.select(List.of("REIMBURSEMENT")),
                List.of(),
                Map.of("startDate", "2026-08-01", "endDate", "2026-08-31")
        );

        assertThat(result.plans()).isEmpty();
        assertThat(result.unavailableSemanticCodes()).containsExactly("REIMBURSEMENT");
    }

    @Test
    void availableTravelAndMissingReimbursementShouldKeepTravelOnly() {
        PersonDatasetPlanService.PlanResult result = service.plan(
                selectionService.select(List.of("TRAVEL", "REIMBURSEMENT")),
                List.of(dataset(DatasetType.TRAVEL, "CFG_TRAVEL")),
                Map.of("startDate", "2026-08-01", "endDate", "2026-08-31")
        );

        assertThat(result.plans()).singleElement().satisfies(plan -> {
            assertThat(plan.type()).isEqualTo(DatasetType.TRAVEL);
            assertThat(plan.datasetCode()).isEqualTo("CFG_TRAVEL");
            assertThat(plan.requestedGrain()).isEqualTo("DAY");
            assertThat(plan.requiredFactCodes())
                    .containsExactly(PersonBusinessQueryService.TRAVEL_RECORDS);
            assertThat(plan.userRequested()).isTrue();
        });
        assertThat(result.unavailableSemanticCodes()).containsExactly("REIMBURSEMENT");
    }

    @Test
    void incompleteAttendanceDependenciesShouldKeepIndependentlyRequestedTravel() {
        List<ReportDataset> configurations = List.of(
                dataset(DatasetType.TRAVEL, "CFG_TRAVEL"),
                dataset(DatasetType.PUNCH, "CFG_PUNCH"),
                dataset(DatasetType.LEAVE, "CFG_LEAVE"),
                dataset(DatasetType.SCHEDULE, "CFG_SCHEDULE")
        );

        PersonDatasetPlanService.PlanResult result = service.plan(
                selectionService.select(List.of("TRAVEL", "ATTENDANCE")),
                configurations,
                Map.of()
        );

        assertThat(result.plans()).singleElement().satisfies(plan -> {
            assertThat(plan.type()).isEqualTo(DatasetType.TRAVEL);
            assertThat(plan.userRequested()).isTrue();
        });
        assertThat(result.unavailableSemanticCodes()).containsExactly("ATTENDANCE");
    }

    @Test
    void completeAttendanceShouldCreateFivePlansAndOnlyPunchIsRequested() {
        List<ReportDataset> configurations = List.of(
                dataset(DatasetType.TRAVEL, "CFG_TRAVEL"),
                dataset(DatasetType.PUNCH, "CFG_PUNCH"),
                dataset(DatasetType.LEAVE, "CFG_LEAVE"),
                dataset(DatasetType.SCHEDULE, "CFG_SCHEDULE"),
                dataset(DatasetType.CALENDAR, "CFG_CALENDAR")
        );

        PersonDatasetPlanService.PlanResult result = service.plan(
                selectionService.select(List.of("ATTENDANCE")),
                configurations,
                Map.of("startDate", "2026-08-01", "endDate", "2026-08-31")
        );

        assertThat(result.plans()).extracting(DatasetPlan::type).containsExactly(
                DatasetType.TRAVEL,
                DatasetType.PUNCH,
                DatasetType.LEAVE,
                DatasetType.SCHEDULE,
                DatasetType.CALENDAR
        );
        assertThat(result.plans()).filteredOn(DatasetPlan::userRequested)
                .extracting(DatasetPlan::type)
                .containsExactly(DatasetType.PUNCH);
        assertThat(result.plans()).allSatisfy(plan -> {
            assertThat(plan.canonicalInput()).containsEntry("startDate", "2026-08-01");
            assertThat(plan.requestedGrain()).isEqualTo("DAY");
            assertThat(plan.requiredFactCodes()).hasSize(1);
        });
        assertThat(result.unavailableSemanticCodes()).isEmpty();
    }

    @Test
    void disabledOrNonPersonConfigurationShouldBeUnavailable() {
        ReportDataset disabled = dataset(DatasetType.REIMBURSEMENT, "CFG_DISABLED");
        disabled.setEnabled(false);
        ReportDataset projectOnly = dataset(DatasetType.REIMBURSEMENT, "CFG_PROJECT");
        projectOnly.setSubjectTypesJson("[\"PROJECT\"]");

        PersonDatasetPlanService.PlanResult result = service.plan(
                selectionService.select(List.of("REIMBURSEMENT")),
                List.of(disabled, projectOnly),
                Map.of()
        );

        assertThat(result.plans()).isEmpty();
        assertThat(result.unavailableSemanticCodes()).containsExactly("REIMBURSEMENT");
    }

    @Test
    void duplicateSelectedTypeConfigurationShouldFailClosed() {
        ReportDataset prefixed = dataset(DatasetType.TRAVEL, "CFG_TRAVEL_A");
        ReportDataset plain = dataset(DatasetType.TRAVEL, "CFG_TRAVEL_B");
        plain.setDomainCode("TRAVEL");

        assertThatThrownBy(() -> service.plan(
                selectionService.select(List.of("TRAVEL")),
                List.of(prefixed, plain),
                Map.of()
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TRAVEL");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "not-json", "{}", "[1]"})
    void invalidSubjectTypesShouldFailClosed(String subjectTypesJson) {
        ReportDataset dataset = dataset(DatasetType.TRAVEL, "CFG_TRAVEL");
        dataset.setSubjectTypesJson(subjectTypesJson);

        PersonDatasetPlanService.PlanResult result = service.plan(
                selectionService.select(List.of("TRAVEL")),
                List.of(dataset),
                Map.of()
        );

        assertThat(result.plans()).isEmpty();
        assertThat(result.unavailableSemanticCodes()).containsExactly("TRAVEL");
    }

    private ReportDataset dataset(DatasetType type, String datasetCode) {
        ReportDataset dataset = new ReportDataset();
        dataset.setDatasetCode(datasetCode);
        dataset.setDomainCode("PERSON_" + type.name());
        dataset.setSubjectTypesJson("[\"PERSON\"]");
        dataset.setEnabled(true);
        return dataset;
    }
}
