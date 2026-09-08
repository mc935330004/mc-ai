package org.example.ai.agent.business.report;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.ai.agent.business.report.model.LogicalReportDocument;
import org.example.ai.agent.business.report.model.LogicalReportDocument.Metric;
import org.example.ai.agent.business.report.model.LogicalReportDocument.ReportTable;
import org.example.ai.agent.business.report.model.LogicalReportDocument.SourceDisclosure;
import org.example.ai.agent.business.report.model.LogicalReportDocument.StatusSection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/** 使用真实数字单元格输出summary/detail双工作表。 */
public final class XlsxReportRenderer implements ReportRenderer {

    private static final int MIN_COLUMN_WIDTH = 12 * 256;
    private static final int MAX_COLUMN_WIDTH = 50 * 256;

    @Override
    public String format() {
        return "XLSX";
    }

    @Override
    public String mimeType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public byte[] render(LogicalReportDocument document) {
        Objects.requireNonNull(document, "document不能为空");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            CellStyle titleStyle = titleStyle(workbook);
            CellStyle sectionStyle = sectionStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle integerStyle = numericStyle(workbook, "0");
            CellStyle decimalStyle = numericStyle(workbook, "0.##########");
            writeSummary(workbook.createSheet("summary"), document,
                    titleStyle, sectionStyle, headerStyle, integerStyle, decimalStyle);
            writeDetail(workbook.createSheet("detail"), document,
                    sectionStyle, headerStyle, integerStyle, decimalStyle);
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("XLSX报告生成失败", exception);
        }
    }

    private void writeSummary(
            Sheet sheet,
            LogicalReportDocument document,
            CellStyle titleStyle,
            CellStyle sectionStyle,
            CellStyle headerStyle,
            CellStyle integerStyle,
            CellStyle decimalStyle) {
        int rowIndex = 0;
        Row title = sheet.createRow(rowIndex++);
        cell(title, 0, document.title()).setCellStyle(titleStyle);
        rowIndex++;
        keyValue(sheet.createRow(rowIndex++), "查询时间", document.queryTime().toString());
        keyValue(sheet.createRow(rowIndex++), "完整性", document.completenessStatement());

        rowIndex = section(sheet, rowIndex + 1, "来源披露", sectionStyle);
        Row sourceHeader = sheet.createRow(rowIndex++);
        styledCell(sourceHeader, 0, "来源", headerStyle);
        styledCell(sourceHeader, 1, "引用", headerStyle);
        for (SourceDisclosure source : document.sources()) {
            Row row = sheet.createRow(rowIndex++);
            cell(row, 0, source.label());
            cell(row, 1, source.reference());
        }

        rowIndex = section(sheet, rowIndex + 1, "关联标签", sectionStyle);
        for (String association : document.associationLabels()) {
            cell(sheet.createRow(rowIndex++), 0, association);
        }

        rowIndex = section(sheet, rowIndex + 1, "指标", sectionStyle);
        Row metricHeader = sheet.createRow(rowIndex++);
        styledCell(metricHeader, 0, "指标", headerStyle);
        styledCell(metricHeader, 1, "值", headerStyle);
        styledCell(metricHeader, 2, "单位", headerStyle);
        for (Metric metric : document.metrics()) {
            Row row = sheet.createRow(rowIndex++);
            cell(row, 0, metric.label());
            if (metric.value() instanceof Number number) {
                numericCell(row, 1, number, integerStyle, decimalStyle);
            } else {
                cell(row, 1, metric.value());
            }
            cell(row, 2, metric.unit());
        }

        rowIndex = section(sheet, rowIndex + 1, "章节状态", sectionStyle);
        Row statusHeader = sheet.createRow(rowIndex++);
        styledCell(statusHeader, 0, "章节", headerStyle);
        styledCell(statusHeader, 1, "状态", headerStyle);
        styledCell(statusHeader, 2, "说明", headerStyle);
        for (StatusSection status : document.statusSections()) {
            Row row = sheet.createRow(rowIndex++);
            cell(row, 0, status.title());
            cell(row, 1, status.state().name());
            cell(row, 2, status.safeMessage());
        }
        setReasonableWidths(sheet, 3);
        sheet.createFreezePane(0, 1);
    }

    private void writeDetail(
            Sheet sheet,
            LogicalReportDocument document,
            CellStyle sectionStyle,
            CellStyle headerStyle,
            CellStyle integerStyle,
            CellStyle decimalStyle) {
        int rowIndex = 0;
        int maximumColumns = 1;
        for (ReportTable table : document.tables()) {
            Row title = sheet.createRow(rowIndex++);
            cell(title, 0, table.title()).setCellStyle(sectionStyle);
            Row header = sheet.createRow(rowIndex++);
            for (int column = 0; column < table.columns().size(); column++) {
                styledCell(header, column, table.columns().get(column), headerStyle);
            }
            for (LogicalReportDocument.TableRow logicalRow : table.rows()) {
                Row row = sheet.createRow(rowIndex++);
                for (int column = 0; column < logicalRow.cells().size(); column++) {
                    Object value = logicalRow.cells().get(column);
                    if (value instanceof Number number) {
                        numericCell(row, column, number, integerStyle, decimalStyle);
                    } else {
                        cell(row, column, value);
                    }
                }
            }
            rowIndex++;
            maximumColumns = Math.max(maximumColumns, table.columns().size());
        }
        sheet.createFreezePane(0, Math.min(2, Math.max(1, rowIndex)));
        setReasonableWidths(sheet, maximumColumns);
    }

    private int section(Sheet sheet, int rowIndex, String text, CellStyle style) {
        Cell cell = cell(sheet.createRow(rowIndex), 0, text);
        cell.setCellStyle(style);
        return rowIndex + 1;
    }

    private void keyValue(Row row, String key, String value) {
        cell(row, 0, key);
        cell(row, 1, value);
    }

    private Cell styledCell(Row row, int column, Object value, CellStyle style) {
        Cell cell = cell(row, column, value);
        cell.setCellStyle(style);
        return cell;
    }

    private Cell cell(Row row, int column, Object value) {
        Cell cell = row.createCell(column);
        if (value != null) {
            cell.setCellValue(ReportScalarFormatter.format(value));
        }
        return cell;
    }

    private Cell numericCell(
            Row row,
            int column,
            Number value,
            CellStyle integerStyle,
            CellStyle decimalStyle) {
        if (!isExcelNumeric(value)) {
            return cell(row, column, value);
        }
        Cell cell = row.createCell(column);
        cell.setCellValue(value.doubleValue());
        cell.setCellStyle(hasFraction(value) ? decimalStyle : integerStyle);
        return cell;
    }

    /** Excel数字最多可靠保留15位有效数字，超出时用精确文本避免静默舍入。 */
    private boolean isExcelNumeric(Number value) {
        double converted = value.doubleValue();
        if (!Double.isFinite(converted)) {
            return false;
        }
        BigDecimal exact = exactDecimal(value).stripTrailingZeros();
        return exact.precision() <= 15
                && exact.compareTo(BigDecimal.valueOf(converted).stripTrailingZeros()) == 0;
    }

    private BigDecimal exactDecimal(Number value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof BigInteger integer) {
            return new BigDecimal(integer);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(value.longValue());
        }
        return new BigDecimal(value.toString());
    }

    private boolean hasFraction(Number value) {
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger) {
            return false;
        }
        return new BigDecimal(value.toString()).stripTrailingZeros().scale() > 0;
    }

    private void setReasonableWidths(Sheet sheet, int columns) {
        for (int column = 0; column < columns; column++) {
            int characters = 12;
            for (Row row : sheet) {
                Cell cell = row.getCell(column);
                if (cell != null) {
                    String value = cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC
                            ? numericText(cell.getNumericCellValue())
                            : cell.toString();
                    characters = Math.max(characters, value.codePointCount(0, value.length()) + 2);
                }
            }
            sheet.setColumnWidth(
                    column,
                    Math.max(MIN_COLUMN_WIDTH, Math.min(MAX_COLUMN_WIDTH, characters * 256))
            );
        }
    }

    private String numericText(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private CellStyle titleStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
        return style;
    }

    private CellStyle sectionStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 12);
        style.setFont(font);
        return style;
    }

    private CellStyle headerStyle(XSSFWorkbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }

    private CellStyle numericStyle(XSSFWorkbook workbook, String format) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat(format));
        return style;
    }
}
