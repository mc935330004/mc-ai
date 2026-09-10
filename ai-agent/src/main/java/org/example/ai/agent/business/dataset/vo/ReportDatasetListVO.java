package org.example.ai.agent.business.dataset.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 报告数据集管理列表项。 */
@Data
public class ReportDatasetListVO {

    private Long id;
    private String datasetCode;
    private String datasetName;
    private String domainCode;
    private List<String> subjectTypes;
    private String queryWorkflowCode;
    private int fieldCount;
    private Boolean enabled;
    private Integer version;
    private LocalDateTime updatedAt;
}
