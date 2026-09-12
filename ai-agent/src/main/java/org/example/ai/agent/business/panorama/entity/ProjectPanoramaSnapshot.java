package org.example.ai.agent.business.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目全景聚合快照。
 *
 * 本表只保存项目定位结果、完整性状态、问题结论和模块快照引用，不保存接口原始响应。
 */
@Data
@TableName("ai_project_panorama_snapshot")
public class ProjectPanoramaSnapshot {

    @TableId(value = "panorama_snapshot_id", type = IdType.INPUT)
    private String panoramaSnapshotId;

    private String sessionId;
    private String userId;
    private String projectId;
    private String projectCode;
    private String projectType;
    private Long profileId;
    private String profileChecksum;
    private String queryJson;
    private String queryHash;
    private String status;
    private Boolean requiredComplete;
    private Boolean allModulesComplete;
    private String issuesJson;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    /**
     * 日志只显示聚合状态，避免会话、项目和查询条件进入日志。
     */
    @Override
    public String toString() {
        return "ProjectPanoramaSnapshot[status=" + status
                + ", requiredComplete=" + requiredComplete
                + ", allModulesComplete=" + allModulesComplete
                + ", expiresAt=" + expiresAt + ']';
    }
}
