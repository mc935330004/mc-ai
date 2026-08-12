package org.example.ai.agent.modelconfig.audit;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.modelconfig.entity.ModelConfigAuditLog;
import org.example.ai.agent.modelconfig.mapper.ModelConfigAuditLogMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 模型配置变更审计记录器。
 *
 * 该组件只负责追加安全审计记录，
 * 不负责业务校验和事务边界。
 */
@Component
@RequiredArgsConstructor
public class ModelConfigAuditRecorder {

    // 中文注释：模型配置操作类型。
    public static final String MODEL_CREATE ="MODEL_CREATE";

    public static final String MODEL_UPDATE =
            "MODEL_UPDATE";

    public static final String MODEL_STATUS_CHANGE =
            "MODEL_STATUS_CHANGE";

    // 中文注释：系统和人员模型授权操作类型。
    public static final String SYSTEM_ASSIGNMENT_SAVE =
            "SYSTEM_ASSIGNMENT_SAVE";

    public static final String USER_ASSIGNMENT_SAVE =
            "USER_ASSIGNMENT_SAVE";

    public static final String USER_ASSIGNMENT_DELETE =
            "USER_ASSIGNMENT_DELETE";

    // 中文注释：审计对象类型。
    public static final String MODEL_CONFIG =
            "MODEL_CONFIG";

    public static final String SYSTEM_ASSIGNMENT =
            "SYSTEM_ASSIGNMENT";

    public static final String USER_ASSIGNMENT =
            "USER_ASSIGNMENT";

    private static final int MAX_DETAIL_LENGTH = 500;

    private final ModelConfigAuditLogMapper auditLogMapper;

    /**
     * 追加一条模型配置审计记录。
     *
     * eventDetail只能传递服务端生成的固定摘要，
     * 禁止传递请求DTO、API Key、密文或完整模型配置。
     */
    public void record(
            String operatorId,
            String actionType,
            String targetType,
            String targetKey,
            String eventDetail) {

        ModelConfigAuditLog auditLog =new ModelConfigAuditLog();
        auditLog.setOperatorId(operatorId);
        auditLog.setActionType(actionType);
        auditLog.setTargetType(targetType);
        auditLog.setTargetKey(targetKey);
        auditLog.setEventDetail(
                normalizeDetail(eventDetail)
        );
        auditLog.setCreatedAt(LocalDateTime.now());

        auditLogMapper.insert(auditLog);
    }

    /**
     * 清理换行并限制摘要长度，
     * 防止审计内容污染页面和数据库字段。
     */
    private String normalizeDetail(String eventDetail) {
        if (!StringUtils.hasText(eventDetail)) {
            return null;
        }

        String normalized = eventDetail
                .replace("\r", " ")
                .replace("\n", " ")
                .trim();

        return normalized.length() <= MAX_DETAIL_LENGTH
                ? normalized
                : normalized.substring(
                        0,
                        MAX_DETAIL_LENGTH
                );
    }
}