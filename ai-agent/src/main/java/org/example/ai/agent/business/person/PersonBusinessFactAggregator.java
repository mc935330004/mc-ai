package org.example.ai.agent.business.person;

import org.example.ai.agent.business.person.PersonBusinessQueryService.DatasetType;
import org.example.ai.agent.business.person.PersonBusinessQueryService.Metric;
import org.example.ai.agent.business.person.PersonBusinessQueryService.ReimbursementSummary;
import org.example.ai.agent.business.person.PersonBusinessQueryService.TravelSummary;
import org.example.ai.agent.business.person.model.AttendanceDayResult;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.example.ai.agent.business.person.PersonBusinessQueryService.CALENDAR_RECORDS;
import static org.example.ai.agent.business.person.PersonBusinessQueryService.LEAVE_RECORDS;
import static org.example.ai.agent.business.person.PersonBusinessQueryService.PUNCH_RECORDS;
import static org.example.ai.agent.business.person.PersonBusinessQueryService.REIMBURSEMENT_RECORDS;
import static org.example.ai.agent.business.person.PersonBusinessQueryService.SCHEDULE_RECORDS;
import static org.example.ai.agent.business.person.PersonBusinessQueryService.TRAVEL_RECORDS;

/**
 * 只处理字段策略已批准的 calculation 事实，不接触来源响应、Spring 或数据库。
 */
public final class PersonBusinessFactAggregator {

    private static final int MAX_ATTENDANCE_DAYS = 366;
    private final AttendanceReconciliationService attendanceService;

    PersonBusinessFactAggregator(AttendanceReconciliationService attendanceService) {
        this.attendanceService = Objects.requireNonNull(
                attendanceService, "attendanceService不能为空"
        );
    }

    /** 根业务事实只使用已约定的发生时间字段，不猜测其他日期。 */
    public static LocalDate occurredOn(DatasetType type, Map<String, Object> record) {
        String field = switch (type) {
            case TRAVEL -> "startAt";
            case PUNCH -> "time";
            case REIMBURSEMENT -> "occurredAt";
            default -> null;
        };
        Object value = field == null ? null : record.get(field);
        if (!(value instanceof String text) || text.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(text.substring(0, 10));
        } catch (DateTimeException exception) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> calculation(
            Map<String, Object> safeFacts,
            Set<String> requiredFactCodes) {
        if (safeFacts == null || !(safeFacts.get("calculation") instanceof Map<?, ?> values)) {
            return null;
        }
        Map<String, Object> typed = (Map<String, Object>) values;
        return typed.keySet().containsAll(requiredFactCodes) ? typed : null;
    }

    TravelSummary travelSummary(SafeFacts data) {
        List<Map<String, Object>> records = records(data, TRAVEL_RECORDS);
        if (records == null) {
            return new TravelSummary(Metric.incomplete(), Metric.incomplete());
        }
        BigDecimal total = BigDecimal.ZERO;
        int approvedCount = 0;
        boolean amountComplete = true;
        for (Map<String, Object> record : records) {
            Object statusValue = record.get("approvalStatus");
            if (!(statusValue instanceof String status) || status.isBlank()) {
                return new TravelSummary(Metric.incomplete(), Metric.incomplete());
            }
            if (!"APPROVED".equals(status.trim().toUpperCase(Locale.ROOT))) {
                continue;
            }
            approvedCount++;
            Object amount = record.get("amount");
            if (!(amount instanceof BigDecimal decimal)) {
                amountComplete = false;
                continue;
            }
            total = total.add(decimal);
        }
        return new TravelSummary(
                Metric.complete(approvedCount),
                amountComplete ? Metric.complete(total) : Metric.incomplete()
        );
    }

    ReimbursementSummary reimbursementSummary(SafeFacts data) {
        List<Map<String, Object>> records = records(data, REIMBURSEMENT_RECORDS);
        if (records == null) {
            return new ReimbursementSummary(
                    Metric.incomplete(), Metric.incomplete(), Metric.incomplete()
            );
        }
        return new ReimbursementSummary(
                sum(records, "requestedAmount"),
                sum(records, "approvedAmount"),
                sum(records, "paidAmount")
        );
    }

    List<AttendanceDayResult> attendance(
            Map<DatasetType, SafeFacts> data,
            Map<String, Object> calendarInput) {
        List<LocalDate> dates = requestedDates(calendarInput);
        if (dates.isEmpty()) {
            return List.of();
        }
        List<CalendarFact> calendars = calendarFacts(data.get(DatasetType.CALENDAR));
        List<ScheduleFact> schedules = scheduleFacts(data.get(DatasetType.SCHEDULE));
        List<AttendanceReconciliationService.Punch> punches = punchFacts(
                data.get(DatasetType.PUNCH)
        );
        List<AttendanceReconciliationService.Exemption> leaves = exemptionFacts(
                data.get(DatasetType.LEAVE), LEAVE_RECORDS
        );
        List<AttendanceReconciliationService.Exemption> travels = exemptionFacts(
                data.get(DatasetType.TRAVEL), TRAVEL_RECORDS
        );
        boolean sourcesValid = calendars != null
                && schedules != null
                && punches != null
                && leaves != null
                && travels != null;

        List<AttendanceDayResult> results = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            AttendanceReconciliationService.WorkCalendar calendar = sourcesValid
                    ? calendarFor(calendars, date) : null;
            AttendanceReconciliationService.WorkSchedule schedule = sourcesValid
                    ? scheduleFor(schedules, date) : null;
            List<AttendanceReconciliationService.Punch> dayPunches = sourcesValid
                    ? punches.stream().filter(value -> date.equals(value.time().toLocalDate())).toList()
                    : List.of();
            results.add(attendanceService.reconcile(new AttendanceReconciliationService.DayInput(
                    date,
                    calendar,
                    schedule,
                    dayPunches,
                    sourcesValid ? leaves : List.of(),
                    sourcesValid ? travels : List.of()
            )));
        }
        return List.copyOf(results);
    }

    private List<LocalDate> requestedDates(Map<String, Object> canonicalInput) {
        try {
            Object startValue = canonicalInput.get("startDate");
            Object endValue = canonicalInput.get("endDate");
            if (!(startValue instanceof String startText)
                    || !(endValue instanceof String endText)) {
                return List.of();
            }
            LocalDate start = LocalDate.parse(startText);
            LocalDate end = LocalDate.parse(endText);
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            if (days < 1 || days > MAX_ATTENDANCE_DAYS) {
                return List.of();
            }
            return java.util.stream.LongStream.range(0, days)
                    .mapToObj(start::plusDays)
                    .toList();
        } catch (DateTimeException exception) {
            return List.of();
        }
    }

    private List<CalendarFact> calendarFacts(SafeFacts data) {
        List<Map<String, Object>> records = records(data, CALENDAR_RECORDS);
        if (records == null) {
            return null;
        }
        List<CalendarFact> facts = new ArrayList<>(records.size());
        try {
            for (Map<String, Object> record : records) {
                if (!(record.get("date") instanceof String date)
                        || !(record.get("workingDay") instanceof Boolean workingDay)) {
                    return null;
                }
                facts.add(new CalendarFact(LocalDate.parse(date), workingDay));
            }
            return List.copyOf(facts);
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private List<ScheduleFact> scheduleFacts(SafeFacts data) {
        List<Map<String, Object>> records = records(data, SCHEDULE_RECORDS);
        if (records == null) {
            return null;
        }
        List<ScheduleFact> facts = new ArrayList<>(records.size());
        try {
            for (Map<String, Object> record : records) {
                if (!(record.get("date") instanceof String date)
                        || !(record.get("startTime") instanceof String start)
                        || !(record.get("endTime") instanceof String end)) {
                    return null;
                }
                facts.add(new ScheduleFact(
                        LocalDate.parse(date), LocalTime.parse(start), LocalTime.parse(end)
                ));
            }
            return List.copyOf(facts);
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private List<AttendanceReconciliationService.Punch> punchFacts(SafeFacts data) {
        List<Map<String, Object>> records = records(data, PUNCH_RECORDS);
        if (records == null) {
            return null;
        }
        List<AttendanceReconciliationService.Punch> facts = new ArrayList<>(records.size());
        try {
            for (Map<String, Object> record : records) {
                if (!(record.get("time") instanceof String time)) {
                    return null;
                }
                facts.add(new AttendanceReconciliationService.Punch(
                        LocalDateTime.parse(time), PUNCH_RECORDS
                ));
            }
            return List.copyOf(facts);
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private List<AttendanceReconciliationService.Exemption> exemptionFacts(
            SafeFacts data,
            String factCode) {
        List<Map<String, Object>> records = records(data, factCode);
        if (records == null) {
            return null;
        }
        List<AttendanceReconciliationService.Exemption> facts = new ArrayList<>(records.size());
        try {
            for (Map<String, Object> record : records) {
                if (!(record.get("startAt") instanceof String start)
                        || !(record.get("endAt") instanceof String end)
                        || !(record.get("approvalStatus") instanceof String status)) {
                    return null;
                }
                facts.add(new AttendanceReconciliationService.Exemption(
                        LocalDateTime.parse(start), LocalDateTime.parse(end), status, factCode
                ));
            }
            return List.copyOf(facts);
        } catch (DateTimeException exception) {
            return null;
        }
    }

    private AttendanceReconciliationService.WorkCalendar calendarFor(
            List<CalendarFact> facts,
            LocalDate date) {
        List<CalendarFact> matches = facts.stream()
                .filter(value -> date.equals(value.date()))
                .toList();
        return matches.size() == 1
                ? new AttendanceReconciliationService.WorkCalendar(
                matches.get(0).workingDay(), CALENDAR_RECORDS
        )
                : null;
    }

    private AttendanceReconciliationService.WorkSchedule scheduleFor(
            List<ScheduleFact> facts,
            LocalDate date) {
        List<ScheduleFact> matches = facts.stream()
                .filter(value -> date.equals(value.date()))
                .toList();
        return matches.size() == 1
                ? new AttendanceReconciliationService.WorkSchedule(
                matches.get(0).start(), matches.get(0).end(), SCHEDULE_RECORDS
        )
                : null;
    }

    private Metric<BigDecimal> sum(List<Map<String, Object>> records, String field) {
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> record : records) {
            Object value = record.get(field);
            if (!(value instanceof BigDecimal decimal)) {
                return Metric.incomplete();
            }
            total = total.add(decimal);
        }
        return Metric.complete(total);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records(SafeFacts data, String factCode) {
        if (data == null || !data.complete()) {
            return null;
        }
        Object value = data.calculation().get(factCode);
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<Map<String, Object>> records = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                return null;
            }
            records.add((Map<String, Object>) map);
        }
        return List.copyOf(records);
    }

    record SafeFacts(boolean complete, Map<String, Object> calculation) {
    }

    private record CalendarFact(LocalDate date, boolean workingDay) {
    }

    private record ScheduleFact(LocalDate date, LocalTime start, LocalTime end) {
    }
}
