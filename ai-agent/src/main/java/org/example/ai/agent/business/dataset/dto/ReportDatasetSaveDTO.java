package org.example.ai.agent.business.dataset.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 报告数据集新增、编辑和校验请求。
 *
 * 页面使用结构化集合，后台负责转换数据库中的 JSON 字段。
 */
@Data
public class ReportDatasetSaveDTO {

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
    private List<FieldDTO> fields;

    /** 报告事实字段及其安全策略。 */
    @Data
    public static class FieldDTO {

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
    }
}
