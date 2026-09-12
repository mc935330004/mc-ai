package org.example.ai.agent.business.panorama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目类型对应的当前全景方案配置。
 */
@Data
@TableName("ai_project_panorama_profile")
public class ProjectPanoramaProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String projectType;
    private String profileName;
    private Boolean enabled;
    private String configChecksum;
    private String createdBy;
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
