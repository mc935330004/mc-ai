package org.example.ai.agent.business.person;

import org.example.ai.agent.business.model.AssociationType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/** 按项目标识和人员有效期对单条业务记录做确定性分类。 */
@Service
public class ProjectRecordAssociationService {

    public AssociationType classify(
            ProjectIdentity target,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<MembershipPeriod> memberships,
            RecordReference record) {
        validate(target, periodStart, periodEnd, memberships, record);
        if (StringUtils.hasText(record.projectCode()) || StringUtils.hasText(record.projectId())) {
            return identityMatches(target, record)
                    ? AssociationType.DIRECT
                    : AssociationType.UNRELATED;
        }
        if (record.occurredOn() == null) {
            return AssociationType.UNKNOWN;
        }
        boolean contextual = contains(periodStart, periodEnd, record.occurredOn())
                && memberships.stream().anyMatch(period ->
                contains(period.start(), period.end(), record.occurredOn()));
        return contextual
                ? AssociationType.PROJECT_PERSON_PERIOD
                : AssociationType.UNKNOWN;
    }

    private void validate(
            ProjectIdentity target,
            LocalDate periodStart,
            LocalDate periodEnd,
            List<MembershipPeriod> memberships,
            RecordReference record) {
        if (target == null
                || periodStart == null
                || periodEnd == null
                || periodStart.isAfter(periodEnd)
                || memberships == null
                || memberships.stream().anyMatch(period -> period == null)
                || record == null) {
            throw new IllegalArgumentException("项目关联条件不完整");
        }
    }

    private boolean identityMatches(ProjectIdentity target, RecordReference record) {
        boolean codeMatches = !StringUtils.hasText(record.projectCode())
                || StringUtils.hasText(target.projectCode())
                && target.projectCode().trim().equals(record.projectCode().trim());
        boolean idMatches = !StringUtils.hasText(record.projectId())
                || StringUtils.hasText(target.projectId())
                && target.projectId().trim().equals(record.projectId().trim());
        return codeMatches && idMatches;
    }

    private boolean contains(LocalDate start, LocalDate end, LocalDate date) {
        return (start == null || !date.isBefore(start))
                && (end == null || !date.isAfter(end));
    }

    /** 目标项目至少提供编码或来源系统项目ID之一。 */
    public record ProjectIdentity(String projectCode, String projectId) {

        public ProjectIdentity {
            if (!StringUtils.hasText(projectCode) && !StringUtils.hasText(projectId)) {
                throw new IllegalArgumentException("目标项目标识不能为空");
            }
        }
    }

    /** 人员在目标项目中的有效期，结束日期允许开放。 */
    public record MembershipPeriod(LocalDate start, LocalDate end) {

        public MembershipPeriod {
            if (start == null || end != null && start.isAfter(end)) {
                throw new IllegalArgumentException("项目成员有效期不合法");
            }
        }
    }

    /** 仅保留关联判断需要的业务记录字段。 */
    public record RecordReference(
            String recordId,
            String projectCode,
            String projectId,
            LocalDate occurredOn) {

        public RecordReference {
            if (!StringUtils.hasText(recordId)) {
                throw new IllegalArgumentException("业务记录标识不能为空");
            }
        }
    }
}
