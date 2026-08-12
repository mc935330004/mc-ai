package org.example.ai.agent.modelconfig.dto;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 模型配置审计日志查询条件。
 */
@Data
public class ModelConfigAuditQueryDTO {

    /**
     * 操作人业务用户编号。
     */
    private String operatorId;

    /**
     * 操作类型。
     */
    private String actionType;

    /**
     * 操作对象类型。
     */
    private String targetType;

    /**
     * 操作对象编号。
     */
    private String targetKey;

    /**
     * 查询开始时间。
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    /**
     * 查询结束时间。
     */
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
}