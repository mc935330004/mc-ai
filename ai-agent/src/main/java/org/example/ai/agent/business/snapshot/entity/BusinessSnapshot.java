package org.example.ai.agent.business.snapshot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通用业务快照元数据和小型安全事实。
 *
 * 大明细仍由 ResultArtifact 分块保存，本表禁止保存来源接口原始响应。
 */
@Data
@TableName("ai_business_snapshot")
public class BusinessSnapshot {

    @TableId(value = "snapshot_id", type = IdType.INPUT)
    private String snapshotId;

    private String sessionId;
    private String userId;
    private String subjectType;
    private String subjectId;
    private String datasetCode;
    private String queryJson;
    private String queryHash;
    private String status;
    private Boolean dataComplete;
    private String factsJson;
    private String configChecksum;
    private String fieldPolicyChecksum;
    private String sourceSnapshotId;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
