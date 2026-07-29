package org.example.ai.agent.alert.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.alert.entity.AlertRecord;
import org.example.ai.agent.alert.entity.AlertRule;
import org.example.ai.agent.alert.mapper.AlertRecordMapper;
import org.example.ai.agent.alert.mapper.AlertRuleMapper;
import org.example.ai.agent.alert.service.AlertService;
import org.example.ai.agent.common.enums.AlertSourceType;
import org.example.ai.agent.common.enums.AlertStatus;
import org.example.ai.agent.common.enums.WorkflowRunStatus;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.file.ContentHashService;
import org.example.ai.agent.workflow.run.entity.WorkflowRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.ai.agent.alert.vo.AlertSummaryVO;

import java.util.List;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 系统告警服务实现。
 */
@Service
@RequiredArgsConstructor
public class AlertServiceImpl extends ServiceImpl<AlertRecordMapper, AlertRecord>
        implements AlertService {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 1000;

    private final AlertRecordMapper alertRecordMapper;
    private final AlertRuleMapper alertRuleMapper;
    private final ContentHashService contentHashService;

    /**
     * 记录工作流失败告警。
     *
     * 告警去重由数据库active_key唯一索引保证，
     * Java代码不执行先查再写。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordWorkflowFailure( WorkflowRun run) {
        if (run == null || !WorkflowRunStatus.FAILED.name() .equals(run.getStatus())) {
            return;
        }

        String errorCode = normalizeErrorCode(
                run.getErrorCode()
        );

        AlertRule rule = findBestRule(
                errorCode
        );

        /*
         * 没有启用规则时不产生告警。
         * 这样管理员可以通过关闭规则停止告警。
         */
        if (rule == null) {
            return;
        }

        String dedupKey = buildDedupKey(
                rule,
                run,
                errorCode
        );

        LocalDateTime occurredAt =run.getFinishedAt() == null
                        ? LocalDateTime.now()
                        : run.getFinishedAt();

        AlertRecord record = new AlertRecord();

        record.setAlertNo(
                createAlertNo()
        );

        record.setRuleId(
                rule.getId()
        );
        record.setRuleCode(
                rule.getRuleCode()
        );
        record.setRuleName(
                rule.getRuleName()
        );

        record.setSeverity(
                rule.getSeverity()
        );
        record.setSourceType(
                AlertSourceType.WORKFLOW_RUN.name()
        );

        record.setFirstSourceId(
                run.getRunId()
        );
        record.setLastSourceId(
                run.getRunId()
        );

        record.setWorkflowId(
                run.getWorkflowId()
        );
        record.setWorkflowCode(
                run.getWorkflowCode()
        );
        record.setWorkflowName(
                run.getWorkflowName()
        );

        record.setErrorCode(errorCode);
        record.setErrorMessage(
                truncate(
                        run.getErrorMessage(),
                        MAX_ERROR_MESSAGE_LENGTH
                )
        );

        record.setDedupKey(dedupKey);
        record.setActiveKey(dedupKey);
        record.setStatus(
                AlertStatus.OPEN.name()
        );
        record.setOccurrenceCount(1);

        record.setFirstOccurredAt(
                occurredAt
        );
        record.setLastOccurredAt(
                occurredAt
        );
        record.setCreatedAt(
                LocalDateTime.now()
        );
        record.setUpdatedAt(
                LocalDateTime.now()
        );

        int affected =
                alertRecordMapper
                        .upsertOccurrence(record);

        /*
         * MySQL新增通常返回1，触发更新通常返回2。
         * 不依赖具体数值，只要求至少影响一行。
         */
        if (affected <= 0) {
            throw new IllegalStateException(
                    "告警记录写入失败"
            );
        }
    }

    /**
     * 确认告警。
     *
     * 只允许OPEN状态转为ACKNOWLEDGED。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void acknowledge( Long alertId, String operatorId) {
        validateOperation(
                alertId,
                operatorId
        );

        LocalDateTime now =
                LocalDateTime.now();

        AlertRecord update =
                new AlertRecord();

        update.setStatus(
                AlertStatus.ACKNOWLEDGED.name()
        );
        update.setAcknowledgedBy(
                operatorId.trim()
        );
        update.setAcknowledgedAt(now);
        update.setUpdatedAt(now);

        int affected =
                alertRecordMapper.update(
                        update,
                        Wrappers.<AlertRecord>lambdaUpdate()
                                .eq(AlertRecord::getId,alertId)
                                .eq(AlertRecord::getStatus,AlertStatus.OPEN.name()));

        if (affected != 1) {
            throw new BusinessException(
                    409,
                    "告警不存在或当前状态不能确认"
            );
        }
    }

    /**
     * 解决告警。
     *
     * 清空activeKey后，相同异常再次发生时，
     * 数据库会创建新的告警记录。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resolve( Long alertId,String operatorId ) {
        validateOperation(alertId,operatorId);

        LocalDateTime now = LocalDateTime.now();

        AlertRecord update = new AlertRecord();

        update.setStatus(AlertStatus.RESOLVED.name() );
        update.setActiveKey(null);
        update.setResolvedBy( operatorId.trim());
        update.setResolvedAt(now);
        update.setUpdatedAt(now);

        /*
         * set(AlertRecord::getActiveKey, null)必须显式保留。
         * MyBatis Plus默认不会通过实体字段更新null值。
         */
        int affected =
                alertRecordMapper.update( update,
                        Wrappers.<AlertRecord>lambdaUpdate()
                                .set(AlertRecord::getActiveKey,null)
                                .eq(AlertRecord::getId,alertId)
                                .in(AlertRecord::getStatus,AlertStatus.OPEN.name(),
                                        AlertStatus.ACKNOWLEDGED.name()));

        if (affected != 1) {
            throw new BusinessException(
                    409,
                    "告警不存在或已经解决"
            );
        }
    }

    /**
     * 分页查询告警。
     */
    @Override
    public Page<AlertRecord> pageAlerts(
            Page<AlertRecord> page,
            String status,
            String severity,
            String workflowCode,
            String errorCode) {
        Page<AlertRecord> safePage = page == null ? new Page<>(1L, 20L): page;
        safePage.setCurrent(
                Math.max(
                        1L,
                        safePage.getCurrent()
                )
        );

        safePage.setSize( Math.max(1L,Math.min(
                                100L,
                                safePage.getSize())) );

        return alertRecordMapper.pageAlerts(
                safePage,
                trimToNull(status),
                trimToNull(severity),
                trimToNull(workflowCode),
                trimToNull(errorCode)
        );
    }

    /**
     * 查询告警详情。
     */
    @Override
    public AlertRecord detail(
            Long alertId
    ) {
        if (alertId == null) {
            throw new BusinessException(
                    400,
                    "告警ID不能为空"
            );
        }

        AlertRecord record =
                alertRecordMapper.selectById(
                        alertId
                );

        if (record == null) {
            throw new BusinessException(
                    404,
                    "告警记录不存在"
            );
        }

        return record;
    }

    /**
     * 查询告警数量汇总。
     */
    @Override
    public AlertSummaryVO summary() {
        AlertSummaryVO summary =
                alertRecordMapper.selectSummary();

        if (summary == null) {
            summary = new AlertSummaryVO();
        }

        summary.setActiveCount(
                zeroIfNull(
                        summary.getActiveCount()
                )
        );
        summary.setOpenCount(
                zeroIfNull(
                        summary.getOpenCount()
                )
        );
        summary.setAcknowledgedCount(
                zeroIfNull(
                        summary.getAcknowledgedCount()
                )
        );
        summary.setCriticalCount(
                zeroIfNull(
                        summary.getCriticalCount()
                )
        );
        summary.setErrorCount(
                zeroIfNull(
                        summary.getErrorCount()
                )
        );
        summary.setWarningCount(
                zeroIfNull(
                        summary.getWarningCount()
                )
        );

        return summary;
    }

    /**
     * 查询告警规则。
     */
    @Override
    public List<AlertRule> listRules() {
        return alertRuleMapper.selectList(
                Wrappers.<AlertRule>lambdaQuery()
                        .orderByAsc(AlertRule::getPriority)
                        .orderByAsc(AlertRule::getId)
        );
    }

    /**
     * 启用或停用告警规则。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setRuleEnabled(Long ruleId,boolean enabled) {
        if (ruleId == null) {
            throw new BusinessException(
                    400,
                    "告警规则ID不能为空"
            );
        }

        AlertRule update =
                new AlertRule();

        update.setId(ruleId);
        update.setEnabled(enabled);
        update.setUpdatedAt(
                LocalDateTime.now()
        );

        if (alertRuleMapper.updateById(update)
                != 1) {
            throw new BusinessException(
                    404,
                    "告警规则不存在"
            );
        }
    }

    private Long zeroIfNull(Long value ) {
        return value == null
                ? 0L
                : value;
    }

    private String trimToNull(String value ) {
        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }

    /**
     * 优先查找错误码完全匹配的规则；
     * 没有精确规则时再使用通用规则。
     */
    private AlertRule findBestRule(String errorCode) {
        AlertRule exactRule =
                alertRuleMapper.selectOne(
                        Wrappers.<AlertRule>lambdaQuery()
                                .eq(AlertRule::getSourceType,AlertSourceType
                                                .WORKFLOW_RUN
                                                .name())
                                .eq(AlertRule::getEnabled,true)
                                .eq(AlertRule::getMatchErrorCode,errorCode )
                                .orderByAsc(AlertRule::getPriority)
                                .last("LIMIT 1")
                );

        if (exactRule != null) {
            return exactRule;
        }

        return alertRuleMapper.selectOne(
                Wrappers.<AlertRule>lambdaQuery()
                        .eq( AlertRule::getSourceType,AlertSourceType
                                        .WORKFLOW_RUN
                                        .name())
                        .eq(AlertRule::getEnabled,
                                true)
                        .isNull(AlertRule::getMatchErrorCode)
                        .orderByAsc(AlertRule::getPriority)
                        .last("LIMIT 1")
        );
    }

    /**
     * 去重范围：
     * 来源类型 + 规则编码 + 工作流身份 + 错误码。
     */
    private String buildDedupKey(
            AlertRule rule,
            WorkflowRun run,
            String errorCode) {
        String workflowIdentity;

        if (run.getWorkflowId() != null) {
            workflowIdentity = String.valueOf(run.getWorkflowId());
        } else if (StringUtils.hasText(run.getWorkflowCode())) {
            workflowIdentity = run.getWorkflowCode().trim();
        } else {
            workflowIdentity = "UNKNOWN_WORKFLOW";
        }

        String source =AlertSourceType.WORKFLOW_RUN.name()
                        + "|"
                        + rule.getRuleCode()
                        + "|"
                        + workflowIdentity
                        + "|"
                        + errorCode;

        return contentHashService.sha256(source);
    }

    private void validateOperation(
            Long alertId,
            String operatorId) {
        if (alertId == null) {
            throw new BusinessException(400, "告警ID不能为空");
        }

        if (!StringUtils.hasText(operatorId)) {
            throw new BusinessException(400,"操作人不能为空");
        }
    }

    private String normalizeErrorCode(String errorCode) {
        return StringUtils.hasText(errorCode)
                ? errorCode.trim()
                : "UNKNOWN_ERROR";
    }

    private String createAlertNo() {
        return "ALT-"+ UUID.randomUUID()
                .toString()
                .replace("-", "");
    }

    private String truncate( String value,int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }

        return value.substring( 0, maxLength);
    }
}