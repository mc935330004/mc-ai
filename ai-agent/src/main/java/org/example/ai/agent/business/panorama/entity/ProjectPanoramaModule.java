package org.example.ai.agent.business.panorama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 全景方案中的一个有序报告数据集模块。
 */
@Data
@TableName("ai_project_panorama_module")
public class ProjectPanoramaModule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long profileId;
    private String datasetCode;
    private Boolean requiredFlag;
    private Integer displayOrder;
    private Integer timeoutMs;
    private String createdBy;
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
