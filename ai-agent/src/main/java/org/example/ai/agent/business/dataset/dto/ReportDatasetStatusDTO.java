package org.example.ai.agent.business.dataset.dto;

import lombok.Data;

/** 报告数据集启停请求。 */
@Data
public class ReportDatasetStatusDTO {

    private Boolean enabled;
    private Integer version;
}
