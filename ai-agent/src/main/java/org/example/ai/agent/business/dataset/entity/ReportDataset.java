package org.example.ai.agent.business.dataset.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报告数据集当前配置。
 *
 * 同一 datasetCode 只保留一行；实际运行使用的配置校验和由后续快照记录。
 */
@Data
@TableName("ai_report_dataset")
public class ReportDataset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String datasetCode;
    private String datasetName;
    private String domainCode;
    private String subjectTypesJson;
    private String queryWorkflowCode;
    private String accessWorkflowCode;
    private String inputMappingJson;
    private Integer ttlMinutes;
    private String associationMode;
    private Integer maxConcurrency;
    private Boolean enabled;
    private String configChecksum;
    private String fieldPolicyChecksum;

    /**
     * 乐观锁版本号，防止多个管理员同时覆盖当前配置。
     */
    @Version
    private Integer version;

    private String createdBy;
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
