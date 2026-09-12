package org.example.ai.agent.business.person;

import org.example.ai.agent.business.person.AttendanceReconciliationService.DayInput;
import org.example.ai.agent.business.person.AttendanceReconciliationService.Exemption;
import org.example.ai.agent.business.person.AttendanceReconciliationService.Punch;
import org.example.ai.agent.business.person.AttendanceReconciliationService.WorkCalendar;
import org.example.ai.agent.business.person.AttendanceReconciliationService.WorkSchedule;
import org.example.ai.agent.business.person.model.AttendanceDayResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AttendanceReconciliationServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 3);

    private final AttendanceReconciliationService service =
            new AttendanceReconciliationService();

    @Test
    void normalPunchesAreDeterminedAsNormal() {
        AttendanceDayResult result = service.reconcile(day(
                workday(), schedule(),
                List.of(punch("08:55", "punch.in"), punch("18:05", "punch.out")),
                List.of(), List.of()
        ));

        assertThat(result.determination()).isEqualTo("NORMAL");
        assertThat(result.rawPunchStatus()).isEqualTo("COMPLETE");
        assertThat(result.exemptionType()).isEqualTo("NONE");
        assertThat(result.evidenceFactCodes())
                .containsExactly("calendar.workday", "schedule.interval", "punch.in", "punch.out");
    }

    @Test
    void missingClockOutWithoutExemptionIsMissingPunch() {
        AttendanceDayResult result = service.reconcile(day(
                workday(), schedule(), List.of(punch("08:55", "punch.in")),
                List.of(), List.of()
        ));

        assertThat(result.determination()).isEqualTo("MISSING_PUNCH");
        assertThat(result.rawPunchStatus()).isEqualTo("MISSING_CLOCK_OUT");
        assertThat(result.exemptionType()).isEqualTo("NONE");
    }

    @Test
    void approvedFullDayLeaveExemptsMissingPunches() {
        AttendanceDayResult result = service.reconcile(day(
                workday(), schedule(), List.of(),
                List.of(exemption("09:00", "18:00", "APPROVED", "leave.approved")),
                List.of()
        ));

        assertThat(result.determination()).isEqualTo("LEAVE_EXEMPT");
        assertThat(result.rawPunchStatus()).isEqualTo("NO_PUNCH");
        assertThat(result.exemptionType()).isEqualTo("LEAVE");
        assertThat(result.evidenceFactCodes()).contains("leave.approved");
    }

    @Test
    void approvedHalfDayTravelExemptsTheTravelInterval() {
        AttendanceDayResult result = service.reconcile(day(
                workday(), schedule(),
                List.of(punch("08:55", "punch.in"), punch("12:00", "punch.out")),
                List.of(),
                List.of(exemption("12:00", "18:00", "APPROVED", "travel.approved"))
        ));

        assertThat(result.determination()).isEqualTo("TRAVEL_EXEMPT");
        assertThat(result.rawPunchStatus()).isEqualTo("COMPLETE");
        assertThat(result.exemptionType()).isEqualTo("TRAVEL");
        assertThat(result.evidenceFactCodes()).contains("travel.approved");
    }

    @Test
    void cancelledLeaveDoesNotExemptMissingPunches() {
        AttendanceDayResult result = service.reconcile(day(
                workday(), schedule(), List.of(),
                List.of(exemption("09:00", "18:00", "CANCELLED", "leave.cancelled")),
                List.of()
        ));

        assertThat(result.determination()).isEqualTo("MISSING_PUNCH");
        assertThat(result.exemptionType()).isEqualTo("NONE");
        assertThat(result.evidenceFactCodes()).doesNotContain("leave.cancelled");
    }

    @Test
    void nonWorkingDayDoesNotRequireScheduleOrPunches() {
        AttendanceDayResult result = service.reconcile(day(
                new WorkCalendar(false, "calendar.restday"), null,
                List.of(), List.of(), List.of()
        ));

        assertThat(result.determination()).isEqualTo("NORMAL");
        assertThat(result.rawPunchStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(result.evidenceFactCodes()).containsExactly("calendar.restday");
    }

    @Test
    void missingCalendarProducesUnknownInsteadOfAbsence() {
        AttendanceDayResult result = service.reconcile(day(
                null, schedule(), List.of(), List.of(), List.of()
        ));

        assertThat(result.determination()).isEqualTo("UNKNOWN");
        assertThat(result.rawPunchStatus()).isEqualTo("NO_PUNCH");
        assertThat(result.exemptionType()).isEqualTo("NONE");
        assertThat(result.evidenceFactCodes()).isEmpty();
    }

    @Test
    void missingScheduleProducesUnknownInsteadOfAbsence() {
        AttendanceDayResult result = service.reconcile(day(
                workday(), null, List.of(), List.of(), List.of()
        ));

        assertThat(result.determination()).isEqualTo("UNKNOWN");
        assertThat(result.rawPunchStatus()).isEqualTo("NO_PUNCH");
        assertThat(result.exemptionType()).isEqualTo("NONE");
        assertThat(result.evidenceFactCodes()).containsExactly("calendar.workday");
    }

    @Test
    void overlappingLeaveAndTravelAreMergedWithoutDoubleExemption() {
        AttendanceDayResult result = service.reconcile(day(
                workday(), schedule(), List.of(punch("16:00", "punch.in")),
                List.of(exemption("09:00", "13:00", "APPROVED", "leave.approved")),
                List.of(exemption("12:00", "16:00", "APPROVED", "travel.approved"))
        ));

        assertThat(result.determination()).isEqualTo("MISSING_PUNCH");
        assertThat(result.rawPunchStatus()).isEqualTo("MISSING_CLOCK_OUT");
        assertThat(result.exemptionType()).isEqualTo("LEAVE_AND_TRAVEL");
        assertThat(result.evidenceFactCodes())
                .containsExactly(
                        "calendar.workday", "schedule.interval", "punch.in",
                        "leave.approved", "travel.approved"
                );
    }

    @Test
    void nullElementsInFactListsAreIgnored() {
        AttendanceDayResult result = service.reconcile(day(
                workday(), schedule(),
                Arrays.asList(null, punch("08:55", "punch.in"), punch("18:05", "punch.out")),
                Arrays.asList((Exemption) null),
                Arrays.asList((Exemption) null)
        ));

        assertThat(result.determination()).isEqualTo("NORMAL");
        assertThat(result.evidenceFactCodes())
                .containsExactly("calendar.workday", "schedule.interval", "punch.in", "punch.out");
    }

    @Test
    void exemptionTouchingOnlyScheduleBoundaryDoesNotExemptWork() {
        AttendanceDayResult result = service.reconcile(day(
                workday(), schedule(), List.of(),
                List.of(exemption("08:00", "09:00", "APPROVED", "leave.before")),
                List.of(exemption("18:00", "19:00", "APPROVED", "travel.after"))
        ));

        assertThat(result.determination()).isEqualTo("MISSING_PUNCH");
        assertThat(result.exemptionType()).isEqualTo("NONE");
        assertThat(result.evidenceFactCodes())
                .containsExactly("calendar.workday", "schedule.interval");
    }

    private DayInput day(
            WorkCalendar calendar,
            WorkSchedule schedule,
            List<Punch> punches,
            List<Exemption> leaves,
            List<Exemption> travels) {
        return new DayInput(DATE, calendar, schedule, punches, leaves, travels);
    }

    private WorkCalendar workday() {
        return new WorkCalendar(true, "calendar.workday");
    }

    private WorkSchedule schedule() {
        return new WorkSchedule(LocalTime.of(9, 0), LocalTime.of(18, 0), "schedule.interval");
    }

    private Punch punch(String time, String factCode) {
        return new Punch(LocalDateTime.of(DATE, LocalTime.parse(time)), factCode);
    }

    private Exemption exemption(
            String start,
            String end,
            String approvalStatus,
            String factCode) {
        return new Exemption(
                LocalDateTime.of(DATE, LocalTime.parse(start)),
                LocalDateTime.of(DATE, LocalTime.parse(end)),
                approvalStatus,
                factCode
        );
    }
}
