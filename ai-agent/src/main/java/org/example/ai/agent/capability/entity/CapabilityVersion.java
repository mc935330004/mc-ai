package org.example.ai.agent.capability.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 能力不可变发布版本。
 *
 * snapshotJson 一旦插入后不能再修改。
 * 后续只允许把 status 从 ACTIVE 更新为 RETIRED。
 */
@Data
@TableName("ai_capability_version")
public class CapabilityVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * ai_capability_definition.id。
     */
    private Long capabilityId;

    /**
     * 稳定能力编码。
     */
    private String capabilityCode;

    /**
     * 能力版本号，从1开始递增。
     */
    private Integer versionNo;

    /**
     * 发布时对应的草稿修订号。
     */
    private Integer configRevision;

    /**
     * 完整、不可变、可执行的配置快照。
     */
    private String snapshotJson;

    /**
     * snapshotJson 的 SHA-256。
     */
    private String configChecksum;

    /**
     * ACTIVE：当前运行版本。
     * RETIRED：历史版本。
     */
    private String status;

    private String publishedBy;

    private LocalDateTime publishedAt;

    private LocalDateTime retiredAt;

    @TableField(fill = FieldFill.INSERT)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
}