package org.example.ai.agent.access.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 能力与工作流人员运行授权。
 */
@Data
@TableName("ai_agent_resource_user_grant")
public class ResourceUserGrant {

    /**
     * 主键ID。
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 资源类型：CAPABILITY、WORKFLOW。
     */
    private String resourceType;

    /**
     * 能力或工作流定义ID。
     */
    private Long resourceId;

    /**
     * PM系统用户编码。
     */
    private String userId;

    /**
     * 配置操作人。
     */
    private String createdBy;

    /**
     * 创建时间。
     */
    private LocalDateTime createdAt;
}
