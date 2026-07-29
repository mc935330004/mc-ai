package org.example.ai.agent.alert.vo;

import lombok.Data;

/**
 * 告警数量汇总。
 */
@Data
public class AlertSummaryVO {

    /**
     * 所有未解决告警数量。
     */
    private Long activeCount;

    /**
     * 待确认告警数量。
     */
    private Long openCount;

    /**
     * 已确认、正在处理的告警数量。
     */
    private Long acknowledgedCount;

    /**
     * 未解决的严重告警数量。
     */
    private Long criticalCount;

    /**
     * 未解决的错误告警数量。
     */
    private Long errorCount;

    /**
     * 未解决的警告告警数量。
     */
    private Long warningCount;
}