package org.example.ai.agent.business.dataset.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据集标准事实及其当前安全字段策略。
 */
@Data
@TableName("ai_report_dataset_field")
public class ReportDatasetField {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasetId;
    private Long fieldId;
    private String factCode;
    private String factName;
    private String factType;
    private Boolean calculable;
    private Boolean displayable;
    private Boolean exportable;
    private Boolean modelVisible;
    private Boolean filterable;
    private String maskStrategy;
    private String grain;
    private Integer displayOrder;
    private String createdBy;
    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
