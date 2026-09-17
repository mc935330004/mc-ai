package org.example.ai.agent.business.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 组合报告异步任务，只保存安全摘要、租约和最终制品元数据。 */
@Data
@TableName("ai_composite_report_task")
public class CompositeReportTask {

    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;
    private String requestKey;
    private String requestFingerprint;
    private String sessionId;
    private String userId;
    private String subjectType;
    private String subjectId;
    private String templateCode;
    private String templateChecksum;
    private String format;
    private String status;
    private Boolean dataComplete;

    /** 产生当前冻结报告的回答运行ID。 */
    private String sourceRunId;

    /** 逻辑报告、来源快照和字段策略共同计算的内容版本。 */
    private String contentVersion;

    /** 经过导出字段策略过滤后的冻结逻辑报告。 */
    private String logicalReportJson;

    /** 逻辑报告冻结时间。 */
    private LocalDateTime frozenAt;

    private String workerId;
    private LocalDateTime leaseUntil;
    private Integer attemptCount;
    private Integer maxAttempts;
    private LocalDateTime nextRetryAt;
    private String storagePath;
    private String fileName;
    private String mimeType;
    private Long fileSize;
    private String checksum;
    private String safeErrorCode;
    private String safeErrorMessage;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    /** 日志只显示安全执行状态，不输出身份、主题、路径或错误细节。 */
    @Override
    public String toString() {
        return "CompositeReportTask[status=" + status
                + ", format=" + format
                + ", dataComplete=" + dataComplete
                + ", contentVersionPresent=" + (contentVersion != null)
                + ", attemptCount=" + attemptCount + ']';
    }
}
