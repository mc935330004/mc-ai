package org.example.ai.agent.business.panorama.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 项目全景的确定性问题规则当前配置。
 */
@Data
@TableName("ai_project_issue_rule")
public class ProjectIssueRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long profileId;
    private String ruleCode;
    private String severity;
    private String conditionJson;
    private String messageTemplate;
    private Boolean enabled;
    private String createdBy;
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
