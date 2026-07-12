package org.example.ai.agent.capability.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * AI 业务系统配置。
 *
 * 一个业务系统只需要配置一次基础地址和 OpenAPI 地址，
 * 后续能力通过 systemCode 关联该系统。
 */
@Data
@TableName("ai_business_system")
public class BusinessSystem {

    /**
     * 主键 ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 业务系统唯一编码。
     *
     * 示例：PM、ERP、OA。
     */
    private String systemCode;

    /**
     * 业务系统名称。
     */
    private String systemName;

    /**
     * 业务系统基础地址。
     *
     * 示例：http://192.168.10.13:8080
     */
    private String baseUrl;

    /**
     * OpenAPI 文档地址。
     *
     * 示例：http://192.168.10.13:8080/v3/api-docs
     */
    private String openapiUrl;

    /**
     * 认证方式。
     *
     * FORWARD：透传当前用户 Authorization。
     * NONE：不携带认证信息。
     */
    private String authType;

    /**
     * 是否启用：1启用，0停用。
     */
    private Integer enabled;

    /**
     * 备注。
     */
    private String remark;

    /**
     * 创建时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.UPDATE)
    private LocalDateTime updatedAt;
}