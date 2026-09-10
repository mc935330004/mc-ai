package org.example.ai.agent.business.dataset.vo;

/** 报告数据集只读校验结果。 */
public record ReportDatasetValidationVO(boolean valid, String message) {
}
