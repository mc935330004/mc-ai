package org.example.ai.agent.modelconfig.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 大模型运行配置。
 */
@Data
@TableName("ai_model_config")
public class ModelConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 稳定模型编码，保存后不允许修改。
     */
    private String modelCode;

    private String displayName;

    private String providerCode;

    private String apiType;

    private String baseUrl;

    /**
     * 加密后的API Key，禁止直接返回前端。
     */
    private String apiKeyCiphertext;

    private String modelName;

    private BigDecimal temperature;

    private Integer maxTokens;

    private Integer timeoutSeconds;

    private Integer streamingSupported;

    private Integer structuredOutputSupported;

    private Integer toolCallingSupported;

    private Integer contextWindow;

    private Integer defaultModel;

    private Integer enabled;

    private Integer sortOrder;

    private String remark;

    private Integer lastTestSuccess;

    private String lastTestMessage;

    private Long lastTestDurationMs;

    private LocalDateTime lastTestAt;
    /**
     * 乐观锁版本号。
     *
     * 防止多个管理员同时修改模型配置时发生数据覆盖。
     */
    @Version
    private Integer version;


    @TableField(fill = FieldFill.INSERT)
    private String createdBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updatedBy;

    /**
     * 创建时间。
     */
    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    /**
     * 更新时间。
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}