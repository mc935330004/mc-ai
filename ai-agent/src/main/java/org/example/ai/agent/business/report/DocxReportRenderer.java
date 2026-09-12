package org.example.ai.agent.business.report;

import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.BreakType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.example.ai.agent.business.report.model.LogicalReportDocument;
import org.example.ai.agent.business.report.model.LogicalReportDocument.Metric;
import org.example.ai.agent.business.report.model.LogicalReportDocument.ReportTable;
import org.example.ai.agent.business.report.model.LogicalReportDocument.SourceDisclosure;
import org.example.ai.agent.business.report.model.LogicalReportDocument.StatusSection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/** 使用标题层级、分页和页眉页脚输出Word报告。 */
public final class DocxReportRenderer implements ReportRenderer {

    @Override
    public String format() {
        return "DOCX";
    }

    @Override
    public String mimeType() {
        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    @Override
    public byte[] render(LogicalReportDocument document) {
        Objects.requireNonNull(document, "document不能为空");
        try (XWPFDocument word = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            writeHeaderAndFooter(word, document.title());
            paragraph(word, document.title(), "Title");

            paragraph(word, "报告说明", "Heading1");
            paragraph(word, "查询时间：" + document.queryTime(), null);
            paragraph(word, "完整性：" + document.completenessStatement(), null);

            paragraph(word, "来源披露", "Heading1");
            for (SourceDisclosure source : document.sources()) {
                paragraph(word, source.label() + "：" + source.reference(), null);
            }

            paragraph(word, "关联标签", "Heading1");
            for (String association : document.associationLabels()) {
                paragraph(word, association, null);
            }

            paragraph(word, "指标", "Heading1");
            XWPFTable metricTable = word.createTable(1, 3);
            metricTable.setWidth("100%");
            header(metricTable.getRow(0), "指标", "值", "单位");
            for (Metric metric : document.metrics()) {
                XWPFTableRow row = metricTable.createRow();
                text(row.getCell(0), metric.label());
                text(row.getCell(1), ReportScalarFormatter.format(metric.value()));
                text(row.getCell(2), metric.unit());
            }

            paragraph(word, "章节状态", "Heading1");
            XWPFTable statusTable = word.createTable(1, 3);
            statusTable.setWidth("100%");
            header(statusTable.getRow(0), "章节", "状态", "说明");
            for (StatusSection status : document.statusSections()) {
                XWPFTableRow row = statusTable.createRow();
                text(row.getCell(0), status.title());
                text(row.getCell(1), status.state().name());
                text(row.getCell(2), status.safeMessage());
            }

            if (!document.tables().isEmpty()) {
                XWPFRun breakRun = word.createParagraph().createRun();
                breakRun.addBreak(BreakType.PAGE);
                paragraph(word, "明细数据", "Heading1");
            }
            for (ReportTable table : document.tables()) {
                paragraph(word, table.title(), "Heading2");
                XWPFTable outputTable = word.createTable(1, table.columns().size());
                outputTable.setWidth("100%");
                header(outputTable.getRow(0), table.columns().toArray(String[]::new));
                for (LogicalReportDocument.TableRow logicalRow : table.rows()) {
                    XWPFTableRow row = outputTable.createRow();
                    for (int column = 0; column < logicalRow.cells().size(); column++) {
                        text(row.getCell(column), ReportScalarFormatter.format(
                                logicalRow.cells().get(column)
                        ));
                    }
                }
            }
            word.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("DOCX报告生成失败", exception);
        }
    }

    private void writeHeaderAndFooter(XWPFDocument word, String title) {
        XWPFHeader header = word.createHeader(HeaderFooterType.DEFAULT);
        paragraph(header.createParagraph(), title, null);
        XWPFFooter footer = word.createFooter(HeaderFooterType.DEFAULT);
        paragraph(footer.createParagraph(), "mc-ai 企业报告", null);
    }

    private XWPFParagraph paragraph(XWPFDocument word, String text, String style) {
        return paragraph(word.createParagraph(), text, style);
    }

    private XWPFParagraph paragraph(XWPFParagraph paragraph, String text, String style) {
        if (style != null) {
            paragraph.setStyle(style);
        }
        paragraph.createRun().setText(text);
        return paragraph;
    }

    private void header(XWPFTableRow row, String... values) {
        for (int column = 0; column < values.length; column++) {
            XWPFTableCell cell = row.getCell(column);
            cell.setText(values[column]);
            cell.getParagraphs().stream()
                    .flatMap(paragraph -> paragraph.getRuns().stream())
                    .forEach(run -> run.setBold(true));
        }
    }

    private void text(XWPFTableCell cell, String value) {
        cell.setText(value == null ? "" : value);
    }

}
