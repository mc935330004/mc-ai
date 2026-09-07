package org.example.ai.agent.business.person;

import org.example.ai.agent.business.model.AssociationType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 按确定性规则区分直接项目记录与项目人员期间上下文。 */
@Service
public class ProjectRecordAssociationService {

    public static final String CONTEXT_LABEL = "项目人员期间关联（不计入项目汇总）";

    public ProjectAssociationResult associate(
            ProjectIdentity targetProject,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<BusinessRecord> records) {
        if (targetProject == null
                || periodStart == null
                || periodEnd == null
                || periodStart.isAfter(periodEnd)) {
            throw new IllegalArgumentException("项目关联条件不完整");
        }
        List<BusinessRecord> safeRecords = records == null ? List.of() : List.copyOf(records);
        List<AssociatedRecord> direct = new ArrayList<>();
        List<AssociatedRecord> context = new ArrayList<>();
        List<BusinessRecord> unrelated = new ArrayList<>();
        ProjectTotals totals = ProjectTotals.zero();

        for (BusinessRecord record : safeRecords) {
            if (record == null) {
                throw new IllegalArgumentException("项目关联记录不能为空");
            }
            AssociationType type = associationType(targetProject, periodStart, periodEnd, record);
            if (type == AssociationType.DIRECT) {
                direct.add(new AssociatedRecord(type, record));
                totals = totals.add(record);
            } else if (type == AssociationType.PROJECT_PERSON_PERIOD) {
                // 人员与期间只能提供分析上下文，绝不能进入项目正式统计。
                context.add(new AssociatedRecord(type, record));
            } else {
                unrelated.add(record);
            }
        }
        return new ProjectAssociationResult(direct, context, unrelated, CONTEXT_LABEL, totals);
    }

    private AssociationType associationType(
            ProjectIdentity targetProject,
            LocalDate periodStart,
            LocalDate periodEnd,
            BusinessRecord record) {
        boolean hasCode = StringUtils.hasText(record.projectCode());
        boolean hasId = StringUtils.hasText(record.projectId());
        if (hasCode || hasId) {
            boolean codeMatches = !hasCode
                    || StringUtils.hasText(targetProject.projectCode())
                    && targetProject.projectCode().trim().equals(record.projectCode().trim());
            boolean idMatches = !hasId
                    || StringUtils.hasText(targetProject.projectId())
                    && targetProject.projectId().trim().equals(record.projectId().trim());
            return codeMatches && idMatches
                    ? AssociationType.DIRECT
                    : AssociationType.UNRELATED;
        }
        boolean recordInPeriod = !record.occurredOn().isBefore(periodStart)
                && !record.occurredOn().isAfter(periodEnd);
        boolean hasRosterPeriod = record.rosterStart() != null || record.rosterEnd() != null;
        boolean rosterOverlapsReport = (record.rosterEnd() == null
                || !record.rosterEnd().isBefore(periodStart))
                && (record.rosterStart() == null
                || !record.rosterStart().isAfter(periodEnd));
        boolean recordInRoster = (record.rosterStart() == null
                || !record.occurredOn().isBefore(record.rosterStart()))
                && (record.rosterEnd() == null
                || !record.occurredOn().isAfter(record.rosterEnd()));
        return hasRosterPeriod && rosterOverlapsReport && recordInPeriod && recordInRoster
                ? AssociationType.PROJECT_PERSON_PERIOD
                : AssociationType.UNRELATED;
    }

    /** 目标项目至少提供编码或来源系统项目ID之一。 */
    public record ProjectIdentity(String projectCode, String projectId) {

        public ProjectIdentity {
            if (!StringUtils.hasText(projectCode) && !StringUtils.hasText(projectId)) {
                throw new IllegalArgumentException("目标项目标识不能为空");
            }
        }
    }

    public record BusinessRecord(
            String recordId,
            String projectCode,
            String projectId,
            LocalDate occurredOn,
            LocalDate rosterStart,
            LocalDate rosterEnd,
            BigDecimal cost,
            BigDecimal hours,
            BigDecimal travelAmount,
            BigDecimal reimbursementAmount) {

        public BusinessRecord {
            if (!StringUtils.hasText(recordId)
                    || occurredOn == null
                    || cost == null
                    || hours == null
                    || travelAmount == null
                    || reimbursementAmount == null) {
                throw new IllegalArgumentException("项目关联记录不完整");
            }
            // 两端都为空表示无成员关系；单端为空分别表示向过去或未来开放。
            if (rosterStart != null && rosterEnd != null && rosterStart.isAfter(rosterEnd)) {
                throw new IllegalArgumentException("项目成员有效期不合法");
            }
        }
    }

    public record AssociatedRecord(AssociationType type, BusinessRecord record) {
    }

    public record ProjectTotals(
            BigDecimal cost,
            BigDecimal hours,
            BigDecimal travelAmount,
            BigDecimal reimbursementAmount) {

        private static ProjectTotals zero() {
            return new ProjectTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        private ProjectTotals add(BusinessRecord record) {
            return new ProjectTotals(
                    cost.add(record.cost()),
                    hours.add(record.hours()),
                    travelAmount.add(record.travelAmount()),
                    reimbursementAmount.add(record.reimbursementAmount())
            );
        }
    }

    public record ProjectAssociationResult(
            List<AssociatedRecord> directRecords,
            List<AssociatedRecord> contextRecords,
            List<BusinessRecord> unrelatedRecords,
            String contextLabel,
            ProjectTotals totals) {

        public ProjectAssociationResult {
            directRecords = List.copyOf(directRecords);
            contextRecords = List.copyOf(contextRecords);
            unrelatedRecords = List.copyOf(unrelatedRecords);
        }
    }
}
