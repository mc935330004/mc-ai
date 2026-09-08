package org.example.ai.agent.business.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 组合报告章节及其安全业务快照引用。 */
@Data
@TableName("ai_composite_report_section")
public class CompositeReportSection {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String taskId;
    private String datasetCode;
    private String snapshotId;
    private String fieldPolicyChecksum;
    private String status;
    private Integer displayOrder;
    private String safeMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 日志不输出快照ID及安全说明，避免组合后形成侧信道。 */
    @Override
    public String toString() {
        return "CompositeReportSection[datasetCode=" + datasetCode
                + ", status=" + status
                + ", displayOrder=" + displayOrder + ']';
    }
}
