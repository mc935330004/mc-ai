package org.example.ai.agent.business.panorama.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目全景模块执行结果与安全业务快照的有序引用。
 */
@Data
@TableName("ai_project_panorama_snapshot_module")
public class ProjectPanoramaSnapshotModule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String panoramaSnapshotId;
    private String datasetCode;
    private String snapshotId;
    private Boolean requiredFlag;
    private Integer displayOrder;
    private String status;
    private LocalDateTime createdAt;
}
