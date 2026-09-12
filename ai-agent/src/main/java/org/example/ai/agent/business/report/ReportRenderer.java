package org.example.ai.agent.business.report;

import org.example.ai.agent.business.report.model.LogicalReportDocument;

/** 报告渲染器只消费已组装的安全逻辑文档。 */
public interface ReportRenderer {

    String format();

    String mimeType();

    byte[] render(LogicalReportDocument document);
}
