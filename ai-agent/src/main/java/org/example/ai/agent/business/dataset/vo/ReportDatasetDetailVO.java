package org.example.ai.agent.business.dataset.vo;

import lombok.Data;
import org.example.ai.agent.business.dataset.dto.ReportDatasetSaveDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 报告数据集当前配置详情。 */
@Data
public class ReportDatasetDetailVO {

    private Long id;
    private Integer version;
    private String datasetCode;
    private String datasetName;
    private String domainCode;
    private List<String> subjectTypes;
    private String queryWorkflowCode;
    private String accessWorkflowCode;
    private Map<String, String> queryInputMapping;
    private Map<String, String> accessInputMapping;
    private Integer ttlMinutes;
    private String associationMode;
    private Integer maxConcurrency;
    private Boolean enabled;
    private List<ReportDatasetSaveDTO.FieldDTO> fields;
    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
