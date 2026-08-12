package org.example.ai.agent.modelconfig.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型配置审计日志返回结果。
 */
@Data
public class ModelConfigAuditVO {

    /**
     * 审计记录编号。
     */
    private Long id;

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
     * 服务端生成的安全操作摘要。
     */
    private String eventDetail;

    /**
     * 操作时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss",timezone = "GMT+8")
    private LocalDateTime createdAt;
}