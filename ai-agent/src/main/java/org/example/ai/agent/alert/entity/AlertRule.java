package org.example.ai.agent.alert.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 告警规则实体。
 */
@Data
@TableName("ai_alert_rule")
public class AlertRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 稳定的规则编码。
     */
    private String ruleCode;

    /**
     * 规则展示名称。
     */
    private String ruleName;

    /**
     * 告警来源类型。
     */
    private String sourceType;

    /**
     * 需要匹配的错误码。
     *
     * 为 null 时表示通用规则，可以匹配任意错误码。
     */
    private String matchErrorCode;

    /**
     * 告警严重等级。
     */
    private String severity;

    /**
     * 匹配优先级，数值越小优先级越高。
     */
    private Integer priority;

    /**
     * 是否启用。
     */
    private Boolean enabled;

    /**
     * 规则说明。
     */
    private String description;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;
}