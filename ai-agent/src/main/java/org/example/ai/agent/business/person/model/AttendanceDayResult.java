package org.example.ai.agent.business.person.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 单日考勤确定性核算结果。
 *
 * evidenceFactCodes 只记录参与本次结论的标准事实编码，不携带原始业务值。
 */
public record AttendanceDayResult(
        LocalDate date,
        String rawPunchStatus,
        String exemptionType,
        String determination,
        List<String> evidenceFactCodes) {

    private static final Set<String> DETERMINATIONS = Set.of(
            "NORMAL", "MISSING_PUNCH", "LEAVE_EXEMPT", "TRAVEL_EXEMPT", "UNKNOWN"
    );

    public AttendanceDayResult {
        Objects.requireNonNull(date, "date不能为空");
        Objects.requireNonNull(rawPunchStatus, "rawPunchStatus不能为空");
        Objects.requireNonNull(exemptionType, "exemptionType不能为空");
        if (!DETERMINATIONS.contains(determination)) {
            throw new IllegalArgumentException("不支持的考勤结论");
        }
        evidenceFactCodes = evidenceFactCodes == null
                ? List.of()
                : List.copyOf(evidenceFactCodes);
    }

    @Override
    public String toString() {
        return "AttendanceDayResult[evidenceFactCodeCount="
                + evidenceFactCodes.size() + ']';
    }
}
