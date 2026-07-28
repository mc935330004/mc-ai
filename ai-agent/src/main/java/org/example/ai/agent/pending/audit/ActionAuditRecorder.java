package org.example.ai.agent.pending.audit;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.pending.entity.ActionAuditLog;
import org.example.ai.agent.pending.entity.PendingAction;
import org.example.ai.agent.pending.mapper.ActionAuditLogMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * WRITE 操作审计记录器。
 *
 * 不增加 Service、ServiceImpl 等多余层级，
 * 直接使用 MyBatis Plus Mapper 追加审计记录。
 */
@Component
@RequiredArgsConstructor
public class ActionAuditRecorder {

    public static final String PREVIEW_CREATED = "PREVIEW_CREATED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";
    public static final String EXPIRED = "EXPIRED";
    public static final String EXECUTION_STARTED = "EXECUTION_STARTED";
    public static final String EXECUTION_SUCCEEDED = "EXECUTION_SUCCEEDED";
    public static final String EXECUTION_FAILED = "EXECUTION_FAILED";

    private final ActionAuditLogMapper actionAuditLogMapper;

    /**
     * 追加一条WRITE操作审计记录。
     *
     * @param action     待确认操作
     * @param eventType 生命周期事件
     * @param detail    安全摘要，只能传错误编码或固定提示
     */
    public void record(PendingAction action,String eventType, String detail) {
        ActionAuditLog auditLog = new ActionAuditLog();
        auditLog.setRunId(action.getRunId());
        auditLog.setUserId(action.getUserId());
        auditLog.setCapabilityCode(action.getCapabilityCode());
        auditLog.setCapabilityName(action.getCapabilityName());
        auditLog.setEventType(eventType);
        auditLog.setEventDetail(normalizeDetail(detail));
        auditLog.setCreatedAt(LocalDateTime.now());
        actionAuditLogMapper.insert(auditLog);
    }

    /**
     * 防止换行污染日志，并限制数据库字段长度。
     */
    private String normalizeDetail(String detail) {
        if (!StringUtils.hasText(detail)) {
            return null;
        }
        String normalized = detail
                .replace("\r", " ")
                .replace("\n", " ");
        return normalized.length() <= 500
                ? normalized
                : normalized.substring(0, 500);
    }
}