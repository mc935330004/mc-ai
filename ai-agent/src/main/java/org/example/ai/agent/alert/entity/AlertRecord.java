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
 * 告警记录实体。
 *
 * 同一个规则、工作流和错误码在未解决期间，
 * 只保留一条活动告警，并累计 occurrenceCount。
 */
@Data
@TableName("ai_alert_record")
public class AlertRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 对外展示的告警编号。
     */
    private String alertNo;

    /**
     * 命中的规则ID。
     */
    private Long ruleId;

    /**
     * 规则编码快照。
     */
    private String ruleCode;

    /**
     * 规则名称快照。
     */
    private String ruleName;

    /**
     * 告警严重等级。
     */
    private String severity;

    /**
     * 告警来源类型。
     */
    private String sourceType;

    /**
     * 首次触发告警的工作流运行ID。
     */
    private String firstSourceId;

    /**
     * 最近一次触发告警的工作流运行ID。
     */
    private String lastSourceId;

    /**
     * 工作流定义ID。
     */
    private Long workflowId;

    /**
     * 工作流编码。
     */
    private String workflowCode;

    /**
     * 工作流名称。
     */
    private String workflowName;

    /**
     * 安全错误码。
     */
    private String errorCode;

    /**
     * 已脱敏、已限制长度的错误信息。
     */
    private String errorMessage;

    /**
     * 永久保存的去重摘要。
     */
    private String dedupKey;

    /**
     * 活动告警唯一键。
     *
     * OPEN、ACKNOWLEDGED 时等于 dedupKey；
     * RESOLVED 后必须设置为 null。
     */
    private String activeKey;

    /**
     * 告警状态。
     */
    private String status;

    /**
     * 相同异常累计发生次数。
     */
    private Integer occurrenceCount;

    /**
     * 首次发生时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime firstOccurredAt;

    /**
     * 最近发生时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime lastOccurredAt;

    /**
     * 确认人。
     */
    private String acknowledgedBy;

    /**
     * 确认时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime acknowledgedAt;

    /**
     * 解决人。
     */
    private String resolvedBy;

    /**
     * 解决时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime resolvedAt;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updatedAt;
}