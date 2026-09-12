package org.example.ai.agent.business.snapshot.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 业务快照的模块、员工或批次执行项。
 */
@Data
@TableName("ai_business_snapshot_item")
public class BusinessSnapshotItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String snapshotId;
    private String itemKey;
    private String workflowCode;
    private Long workflowVersionId;
    private Integer workflowVersionNo;
    private String workflowConfigChecksum;
    private String workflowRunId;
    private String resultArtifactId;
    private String status;
    private String associationType;
    private Integer totalCount;
    private Integer successCount;
    private Integer failureCount;
    private String safeErrorCode;
    private String safeErrorMessage;
    private LocalDateTime createdAt;
}
