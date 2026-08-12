package org.example.ai.agent.modelconfig.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型配置和模型授权变更审计记录。
 *
 * 只保存操作身份、动作和安全摘要，
 * 禁止保存API Key、密文和完整配置。
 */
@Data
@TableName("ai_model_config_audit_log")
public class ModelConfigAuditLog {

    /**
     * 审计记录ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 执行管理操作的真实业务用户ID。
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
     * 模型编码、SYSTEM或者目标用户ID。
     */
    private String targetKey;

    /**
     * 服务端生成的安全操作摘要。
     */
    private String eventDetail;

    /**
     * 操作发生时间。
     */
    @JsonFormat(
            pattern = "yyyy-MM-dd HH:mm:ss",
            timezone = "GMT+8"
    )
    private LocalDateTime createdAt;
}