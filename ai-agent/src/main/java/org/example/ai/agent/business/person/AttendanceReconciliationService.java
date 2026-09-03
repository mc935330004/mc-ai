package org.example.ai.agent.business.person;

import org.example.ai.agent.business.person.model.AttendanceDayResult;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * 根据日历、排班、审批区间和打卡事实确定性核算单日考勤。
 *
 * 日期和区间运算全部在本服务完成，模型只能消费结果，不能改写正式结论。
 */
@Service
public class AttendanceReconciliationService {

    private static final String APPROVED = "APPROVED";

    public AttendanceDayResult reconcile(DayInput input) {
        if (input == null || input.date() == null) {
            throw new IllegalArgumentException("考勤日输入不能为空");
        }
        List<Punch> punches = validPunches(input);
        if (input.calendar() == null) {
            return result(input.date(), punchStatus(punches, null), "NONE", "UNKNOWN", List.of());
        }

        LinkedHashSet<String> evidence = new LinkedHashSet<>();
        addEvidence(evidence, input.calendar().factCode());
        if (!input.calendar().workingDay()) {
            return result(input.date(), "NOT_REQUIRED", "NONE", "NORMAL", evidence);
        }
        if (!validSchedule(input.schedule())) {
            return result(input.date(), punchStatus(punches, null), "NONE", "UNKNOWN", evidence);
        }

        addEvidence(evidence, input.schedule().factCode());
        TimeRange schedule = new TimeRange(
                LocalDateTime.of(input.date(), input.schedule().start()),
                LocalDateTime.of(input.date(), input.schedule().end())
        );
        List<TypedExemption> exemptions = approvedExemptions(input, schedule);
        List<TimeRange> required = subtract(schedule, exemptions);
        punches.forEach(punch -> addEvidence(evidence, punch.factCode()));
        exemptions.forEach(exemption -> addEvidence(evidence, exemption.factCode()));

        String exemptionType = exemptionType(exemptions);
        String rawStatus = punchStatus(punches, required);
        if (required.isEmpty()) {
            return result(
                    input.date(), rawStatus, exemptionType,
                    exemptDetermination(exemptionType), evidence
            );
        }
        if (!punchesCover(required, punches)) {
            return result(input.date(), rawStatus, exemptionType, "MISSING_PUNCH", evidence);
        }
        String determination = "NONE".equals(exemptionType)
                ? "NORMAL"
                : exemptDetermination(exemptionType);
        return result(input.date(), rawStatus, exemptionType, determination, evidence);
    }

    private List<Punch> validPunches(DayInput input) {
        return input.punches().stream()
                .filter(Objects::nonNull)
                .filter(punch -> punch.time() != null)
                .filter(punch -> input.date().equals(punch.time().toLocalDate()))
                .sorted(Comparator.comparing(Punch::time))
                .toList();
    }

    private boolean validSchedule(WorkSchedule schedule) {
        return schedule != null
                && schedule.start() != null
                && schedule.end() != null
                && schedule.start().isBefore(schedule.end());
    }

    private List<TypedExemption> approvedExemptions(DayInput input, TimeRange schedule) {
        List<TypedExemption> approved = new ArrayList<>();
        collectApproved(approved, input.leaves(), "LEAVE", schedule);
        collectApproved(approved, input.travels(), "TRAVEL", schedule);
        approved.sort(Comparator.comparing((TypedExemption value) -> value.range().start())
                .thenComparing(value -> value.range().end())
                .thenComparing(TypedExemption::type)
                .thenComparing(value -> Objects.toString(value.factCode(), "")));
        return List.copyOf(approved);
    }

    private void collectApproved(
            List<TypedExemption> target,
            List<Exemption> source,
            String type,
            TimeRange schedule) {
        for (Exemption exemption : source) {
            if (exemption == null
                    || exemption.start() == null
                    || exemption.end() == null
                    || !APPROVED.equals(normalize(exemption.approvalStatus()))) {
                continue;
            }
            LocalDateTime start = exemption.start().isAfter(schedule.start())
                    ? exemption.start() : schedule.start();
            LocalDateTime end = exemption.end().isBefore(schedule.end())
                    ? exemption.end() : schedule.end();
            if (start.isBefore(end)) {
                target.add(new TypedExemption(new TimeRange(start, end), type, exemption.factCode()));
            }
        }
    }

    /**
     * 逐个扣除已排序的审批区间；重叠部分自然只扣除一次，不按时长重复累计。
     */
    private List<TimeRange> subtract(TimeRange schedule, List<TypedExemption> exemptions) {
        List<TimeRange> required = List.of(schedule);
        for (TypedExemption exemption : exemptions) {
            List<TimeRange> next = new ArrayList<>();
            for (TimeRange range : required) {
                if (!range.overlaps(exemption.range())) {
                    next.add(range);
                    continue;
                }
                if (range.start().isBefore(exemption.range().start())) {
                    next.add(new TimeRange(range.start(), exemption.range().start()));
                }
                if (range.end().isAfter(exemption.range().end())) {
                    next.add(new TimeRange(exemption.range().end(), range.end()));
                }
            }
            required = List.copyOf(next);
        }
        return required;
    }

    private boolean punchesCover(List<TimeRange> required, List<Punch> punches) {
        if (punches.size() < 2) {
            return false;
        }
        LocalDateTime first = punches.get(0).time();
        LocalDateTime last = punches.get(punches.size() - 1).time();
        return required.stream().allMatch(range ->
                !first.isAfter(range.start()) && !last.isBefore(range.end())
        );
    }

    private String punchStatus(List<Punch> punches, List<TimeRange> required) {
        if (punches.isEmpty()) {
            return "NO_PUNCH";
        }
        if (punches.size() >= 2) {
            return "COMPLETE";
        }
        if (required == null || required.isEmpty()) {
            return "SINGLE_PUNCH";
        }
        return punches.get(0).time().isAfter(required.get(0).start())
                ? "MISSING_CLOCK_IN"
                : "MISSING_CLOCK_OUT";
    }

    private String exemptionType(List<TypedExemption> exemptions) {
        Set<String> types = exemptions.stream()
                .map(TypedExemption::type)
                .collect(java.util.stream.Collectors.toSet());
        if (types.contains("LEAVE") && types.contains("TRAVEL")) {
            return "LEAVE_AND_TRAVEL";
        }
        if (types.contains("LEAVE")) {
            return "LEAVE";
        }
        if (types.contains("TRAVEL")) {
            return "TRAVEL";
        }
        return "NONE";
    }

    private String exemptDetermination(String exemptionType) {
        return "TRAVEL".equals(exemptionType) ? "TRAVEL_EXEMPT" : "LEAVE_EXEMPT";
    }

    private AttendanceDayResult result(
            LocalDate date,
            String rawPunchStatus,
            String exemptionType,
            String determination,
            Iterable<String> evidence) {
        List<String> codes = new ArrayList<>();
        evidence.forEach(codes::add);
        return new AttendanceDayResult(
                date, rawPunchStatus, exemptionType, determination, codes
        );
    }

    private void addEvidence(Set<String> evidence, String factCode) {
        if (StringUtils.hasText(factCode)) {
            evidence.add(factCode.trim());
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    /** 单日核算所需的最小事实集合。 */
    public record DayInput(
            LocalDate date,
            WorkCalendar calendar,
            WorkSchedule schedule,
            List<Punch> punches,
            List<Exemption> leaves,
            List<Exemption> travels) {

        public DayInput {
            punches = nonNullCopy(punches);
            leaves = nonNullCopy(leaves);
            travels = nonNullCopy(travels);
        }
    }

    /** 来源列表允许稀疏元素，进入核算前统一过滤并冻结。 */
    private static <T> List<T> nonNullCopy(List<T> source) {
        return source == null
                ? List.of()
                : source.stream().filter(Objects::nonNull).toList();
    }

    /** 工作日历事实；workingDay=false 时不要求排班和打卡。 */
    public record WorkCalendar(boolean workingDay, String factCode) {
    }

    /** 当日预期上下班区间；当前契约不接受跨日排班。 */
    public record WorkSchedule(LocalTime start, LocalTime end, String factCode) {
    }

    /** 一次原始打卡事实。 */
    public record Punch(LocalDateTime time, String factCode) {
    }

    /** 请假或出差审批区间，只有 APPROVED 状态参与核算。 */
    public record Exemption(
            LocalDateTime start,
            LocalDateTime end,
            String approvalStatus,
            String factCode) {
    }

    private record TimeRange(LocalDateTime start, LocalDateTime end) {
        private boolean overlaps(TimeRange other) {
            return start.isBefore(other.end()) && end.isAfter(other.start());
        }
    }

    private record TypedExemption(TimeRange range, String type, String factCode) {
    }
}
