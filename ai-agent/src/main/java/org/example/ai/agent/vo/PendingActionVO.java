package org.example.ai.agent.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 待确认操作返回对象。
 *
 * 不向前端暴露数据库ID和幂等键。
 */
@Data
@Builder
public class PendingActionVO {

    private String runId;

    private String capabilityCode;

    private String capabilityName;

    private Map<String, Object> input;

    private String actionSummary;

    private String status;

    private LocalDateTime expireAt;

    private LocalDateTime confirmedAt;

    private LocalDateTime executedAt;

    private String errorMessage;
    /**
     * 业务系统执行结果。
     */
    private Object output;

    /**
     * 面向聊天界面展示的 Markdown 操作结果。
     */
    private String markdown;
}