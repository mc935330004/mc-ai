package org.example.ai.agent.business.person;

import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.example.ai.agent.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 人员业务数据集语义选择和依赖闭包测试。
 */
class PersonDatasetSelectionServiceTest {

    private final PersonDatasetSelectionService service =
            new PersonDatasetSelectionService();

    @Test
    void defaultsToCompletePersonOverview() {
        PersonDatasetSelectionService.Selection selection = service.select(List.of());

        assertThat(selection.semanticCodes())
                .containsExactly("TRAVEL", "ATTENDANCE", "REIMBURSEMENT");
        assertThat(selection.requestedTypes())
                .containsExactlyInAnyOrder(
                        DatasetType.TRAVEL,
                        DatasetType.PUNCH,
                        DatasetType.REIMBURSEMENT
                );
        assertThat(selection.executionTypes()).containsExactly(DatasetType.values());
    }

    @Test
    void selectsTravelOnly() {
        PersonDatasetSelectionService.Selection selection =
                service.select(List.of("TRAVEL"));

        assertThat(selection.semanticCodes()).containsExactly("TRAVEL");
        assertThat(selection.executionTypes()).containsExactly(DatasetType.TRAVEL);
        assertThat(selection.requested(DatasetType.TRAVEL)).isTrue();
        assertThat(selection.requested(DatasetType.PUNCH)).isFalse();
    }

    @Test
    void selectsReimbursementOnly() {
        PersonDatasetSelectionService.Selection selection =
                service.select(List.of("REIMBURSEMENT"));

        assertThat(selection.semanticCodes()).containsExactly("REIMBURSEMENT");
        assertThat(selection.executionTypes()).containsExactly(DatasetType.REIMBURSEMENT);
    }

    @Test
    void expandsAttendanceToFiveDatasets() {
        PersonDatasetSelectionService.Selection selection =
                service.select(List.of("ATTENDANCE"));

        assertThat(selection.semanticCodes()).containsExactly("ATTENDANCE");
        assertThat(selection.requestedTypes()).containsExactly(DatasetType.PUNCH);
        assertThat(selection.executionTypes()).containsExactly(
                DatasetType.TRAVEL,
                DatasetType.PUNCH,
                DatasetType.LEAVE,
                DatasetType.SCHEDULE,
                DatasetType.CALENDAR
        );
    }

    @Test
    void combinesAttendanceAndReimbursementInEnumOrder() {
        PersonDatasetSelectionService.Selection selection =
                service.select(List.of("REIMBURSEMENT", "ATTENDANCE"));

        assertThat(selection.semanticCodes())
                .containsExactly("ATTENDANCE", "REIMBURSEMENT");
        assertThat(selection.executionTypes()).containsExactly(DatasetType.values());
    }

    @Test
    void deduplicatesAttendanceTravelDependency() {
        PersonDatasetSelectionService.Selection selection =
                service.select(List.of("ATTENDANCE", "TRAVEL"));

        assertThat(selection.semanticCodes()).containsExactly("TRAVEL", "ATTENDANCE");
        assertThat(selection.requestedTypes())
                .containsExactlyInAnyOrder(DatasetType.TRAVEL, DatasetType.PUNCH);
        assertThat(selection.executionTypes()).containsExactly(
                DatasetType.TRAVEL,
                DatasetType.PUNCH,
                DatasetType.LEAVE,
                DatasetType.SCHEDULE,
                DatasetType.CALENDAR
        );
    }

    @Test
    void treatsPunchAsAttendanceAlias() {
        PersonDatasetSelectionService.Selection selection =
                service.select(List.of("PUNCH"));

        assertThat(selection.semanticCodes()).containsExactly("ATTENDANCE");
        assertThat(selection.requestedTypes()).containsExactly(DatasetType.PUNCH);
        assertThat(selection.executionTypes()).containsExactly(
                DatasetType.TRAVEL,
                DatasetType.PUNCH,
                DatasetType.LEAVE,
                DatasetType.SCHEDULE,
                DatasetType.CALENDAR
        );
    }

    @Test
    void rejectsUnsupportedPersonDatasetWithoutFallingBackToAll() {
        assertThatThrownBy(() -> service.select(List.of("LEAVE")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("暂不支持该人员业务数据类型，请查询出差、考勤或报销")
                .satisfies(exception -> assertThat(((BusinessException) exception).getCode())
                        .isEqualTo(400));
    }

    @Test
    void freezesSelectionCollections() {
        PersonDatasetSelectionService.Selection selection =
                service.select(List.of("TRAVEL"));

        assertThatThrownBy(() -> selection.semanticCodes().add("ATTENDANCE"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> selection.requestedTypes().add(DatasetType.PUNCH))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> selection.executionTypes().add(DatasetType.PUNCH))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
