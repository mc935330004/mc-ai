package org.example.ai.agent.business.report;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.example.ai.agent.business.report.model.LogicalReportDocument;
import org.example.ai.agent.business.report.model.LogicalReportDocument.Metric;
import org.example.ai.agent.business.report.model.LogicalReportDocument.ReportTable;
import org.example.ai.agent.business.report.model.LogicalReportDocument.SourceDisclosure;
import org.example.ai.agent.business.report.model.LogicalReportDocument.StatusSection;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** 使用调用方明确配置的可嵌入中文字体生成分页PDF。 */
public final class PdfReportRenderer implements ReportRenderer {

    private final Path fontPath;

    /** 构造阶段不访问文件系统，避免未配置字体导致应用上下文启动失败。 */
    public PdfReportRenderer(Path fontPath) {
        this.fontPath = fontPath == null ? null : fontPath.toAbsolutePath().normalize();
    }

    @Override
    public String format() {
        return "PDF";
    }

    @Override
    public String mimeType() {
        return "application/pdf";
    }

    @Override
    public byte[] render(LogicalReportDocument document) {
        Objects.requireNonNull(document, "document不能为空");
        validateFont();
        try (PDDocument pdf = new PDDocument();
             InputStream fontInput = Files.newInputStream(fontPath);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            // embedSubset=true，保存时只写入报告实际使用的字形。
            PDType0Font font = PDType0Font.load(pdf, fontInput, true);
            try (PdfWriter writer = new PdfWriter(pdf, font, document.title())) {
                writeDocument(writer, document);
            }
            pdf.save(output);
            return output.toByteArray();
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "PDF报告生成失败：配置的中文字体不可读或格式不受支持",
                    exception
            );
        }
    }

    private void validateFont() {
        if (fontPath == null) {
            throw new IllegalStateException("PDF中文字体路径未配置");
        }
        String fileName = Objects.toString(fontPath.getFileName(), "")
                .toLowerCase(Locale.ROOT);
        if ((!fileName.endsWith(".ttf") && !fileName.endsWith(".otf"))
                || !Files.isRegularFile(fontPath)
                || !Files.isReadable(fontPath)) {
            throw new IllegalStateException("PDF中文字体不可读或格式不受支持");
        }
    }

    private void writeDocument(PdfWriter writer, LogicalReportDocument document)
            throws IOException {
        writer.paragraph(document.title(), 18, 24);
        writer.paragraph("报告说明", 14, 20);
        writer.paragraph("查询时间：" + document.queryTime(), 10, 15);
        writer.paragraph("完整性：" + document.completenessStatement(), 10, 15);

        writer.paragraph("来源披露", 14, 20);
        for (SourceDisclosure source : document.sources()) {
            writer.paragraph(source.label() + "：" + source.reference(), 10, 15);
        }

        writer.paragraph("关联标签", 14, 20);
        for (String association : document.associationLabels()) {
            writer.paragraph(association, 10, 15);
        }

        writer.paragraph("指标", 14, 20);
        for (Metric metric : document.metrics()) {
            writer.paragraph(
                    metric.label() + "：" + ReportScalarFormatter.format(metric.value())
                            + Objects.toString(metric.unit(), ""),
                    10,
                    15
            );
        }

        writer.paragraph("章节状态", 14, 20);
        for (StatusSection status : document.statusSections()) {
            writer.paragraph(
                    status.title() + " [" + status.state().name() + "]："
                            + status.safeMessage(),
                    10,
                    15
            );
        }

        if (!document.tables().isEmpty()) {
            writer.pageBreak();
            writer.paragraph("明细数据", 14, 20);
            for (ReportTable table : document.tables()) {
                writer.table(table);
            }
        }
    }

    /** 管理简单A4布局，并在每次换页后重新绘制表头。 */
    private static final class PdfWriter implements AutoCloseable {

        private static final float MARGIN = 40;
        private static final float BOTTOM = 45;
        private static final float MIN_TABLE_ROW_HEIGHT = 20;
        private static final float TABLE_LINE_HEIGHT = 10;
        private static final float TABLE_PADDING = 4;
        private static final float TABLE_FONT_SIZE = 8;
        private static final String TABLE_HEADER_TOO_HIGH = "PDF表头过高，无法分页";

        private final PDDocument document;
        private final PDFont font;
        private final String reportTitle;
        private PDPage page;
        private PDPageContentStream stream;
        private float y;
        private int pageNumber;

        private PdfWriter(PDDocument document, PDFont font, String reportTitle)
                throws IOException {
            this.document = document;
            this.font = font;
            this.reportTitle = reportTitle;
            newPage();
        }

        private void paragraph(String text, float fontSize, float leading) throws IOException {
            List<String> lines = wrap(text, page.getMediaBox().getWidth() - 2 * MARGIN, fontSize);
            for (String line : lines) {
                ensureSpace(leading);
                text(line, MARGIN, y - fontSize, fontSize);
                y -= leading;
            }
            y -= 3;
        }

        private void table(ReportTable table) throws IOException {
            ensureSpace(90);
            paragraph(table.title(), 12, 18);
            List<Float> widths = columnWidths(table.columns().size());
            List<List<String>> headerLines = wrapCells(table.columns(), widths, 9);
            float headerHeight = rowHeight(maxLineCount(headerLines));
            validateFreshPageCapacity(headerHeight);
            if (y - headerHeight - MIN_TABLE_ROW_HEIGHT < BOTTOM) {
                newTablePage(headerLines, widths, headerHeight);
            } else {
                drawRowSegment(
                        headerLines, widths, 0, maxLineCount(headerLines), headerHeight, 9
                );
            }
            for (LogicalReportDocument.TableRow row : table.rows()) {
                List<String> values = row.cells().stream()
                        .map(ReportScalarFormatter::format)
                        .toList();
                List<List<String>> wrapped = wrapCells(values, widths, TABLE_FONT_SIZE);
                writeTableRow(wrapped, widths, headerLines, headerHeight);
            }
            y -= 10;
        }

        private void writeTableRow(
                List<List<String>> wrapped,
                List<Float> widths,
                List<List<String>> headerLines,
                float headerHeight) throws IOException {
            int lineCount = maxLineCount(wrapped);
            float fullHeight = rowHeight(lineCount);
            float freshPageCapacity = page.getMediaBox().getHeight()
                    - MARGIN - 24 - headerHeight - BOTTOM;
            if (fullHeight <= freshPageCapacity) {
                if (y - fullHeight < BOTTOM) {
                    newTablePage(headerLines, widths, headerHeight);
                }
                drawRowSegment(
                        wrapped, widths, 0, lineCount, fullHeight, TABLE_FONT_SIZE
                );
                return;
            }

            // 单行超过一页时按文本行分段，续页仍重复表头且不丢弃任何事实。
            int fromLine = 0;
            while (fromLine < lineCount) {
                int availableLines = availableTableLines();
                if (availableLines == 0) {
                    newTablePage(headerLines, widths, headerHeight);
                    availableLines = requireAvailableTableLines();
                }
                int segmentLines = Math.min(availableLines, lineCount - fromLine);
                if (segmentLines <= 0) {
                    throw new IllegalStateException(TABLE_HEADER_TOO_HIGH);
                }
                drawRowSegment(
                        wrapped,
                        widths,
                        fromLine,
                        segmentLines,
                        rowHeight(segmentLines),
                        TABLE_FONT_SIZE
                );
                fromLine += segmentLines;
                if (fromLine < lineCount) {
                    newTablePage(headerLines, widths, headerHeight);
                }
            }
        }

        private void newTablePage(
                List<List<String>> headerLines,
                List<Float> widths,
                float headerHeight) throws IOException {
            newPage();
            validateFreshPageCapacity(headerHeight);
            drawRowSegment(
                    headerLines, widths, 0, maxLineCount(headerLines), headerHeight, 9
            );
            requireAvailableTableLines();
        }

        /** 表头必须给至少一行数据留出空间，避免续页循环无法推进。 */
        private void validateFreshPageCapacity(float headerHeight) {
            float freshPageCapacity = page.getMediaBox().getHeight()
                    - MARGIN - 24 - headerHeight - BOTTOM;
            if (freshPageCapacity < MIN_TABLE_ROW_HEIGHT) {
                throw new IllegalStateException(TABLE_HEADER_TOO_HIGH);
            }
        }

        private int requireAvailableTableLines() {
            int availableLines = availableTableLines();
            if (availableLines <= 0) {
                throw new IllegalStateException(TABLE_HEADER_TOO_HIGH);
            }
            return availableLines;
        }

        private void drawRowSegment(
                List<List<String>> lines,
                List<Float> widths,
                int fromLine,
                int lineCount,
                float height,
                float fontSize) throws IOException {
            float x = MARGIN;
            for (int column = 0; column < lines.size(); column++) {
                float width = widths.get(column);
                stream.addRect(x, y - height, width, height);
                stream.stroke();
                List<String> cellLines = lines.get(column);
                float baseline = y - TABLE_PADDING - fontSize;
                int untilLine = Math.min(cellLines.size(), fromLine + lineCount);
                for (int line = fromLine; line < untilLine; line++) {
                    text(cellLines.get(line), x + TABLE_PADDING, baseline, fontSize);
                    baseline -= TABLE_LINE_HEIGHT;
                }
                x += width;
            }
            y -= height;
        }

        private List<List<String>> wrapCells(
                List<String> values,
                List<Float> widths,
                float fontSize) throws IOException {
            List<List<String>> result = new ArrayList<>(values.size());
            for (int column = 0; column < values.size(); column++) {
                result.add(wrap(
                        values.get(column), widths.get(column) - 2 * TABLE_PADDING, fontSize
                ));
            }
            return result;
        }

        private int maxLineCount(List<List<String>> lines) {
            return lines.stream().mapToInt(List::size).max().orElse(1);
        }

        private float rowHeight(int lineCount) {
            return Math.max(
                    MIN_TABLE_ROW_HEIGHT,
                    lineCount * TABLE_LINE_HEIGHT + 2 * TABLE_PADDING
            );
        }

        private int availableTableLines() {
            float availableHeight = y - BOTTOM;
            if (availableHeight < MIN_TABLE_ROW_HEIGHT) {
                return 0;
            }
            return Math.max(
                    1,
                    (int) Math.floor(
                            (availableHeight - 2 * TABLE_PADDING) / TABLE_LINE_HEIGHT
                    )
            );
        }

        private void pageBreak() throws IOException {
            newPage();
        }

        private void ensureSpace(float required) throws IOException {
            if (y - required < BOTTOM) {
                newPage();
            }
        }

        private void newPage() throws IOException {
            closePage();
            page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            pageNumber++;
            y = page.getMediaBox().getHeight() - MARGIN;
            text(reportTitle, MARGIN, y - 9, 9);
            y -= 24;
        }

        private void closePage() throws IOException {
            if (stream == null) {
                return;
            }
            text("第" + pageNumber + "页", MARGIN, 22, 8);
            stream.close();
            stream = null;
        }

        private void text(String value, float x, float baseline, float fontSize)
                throws IOException {
            stream.beginText();
            stream.setFont(font, fontSize);
            stream.newLineAtOffset(x, baseline);
            stream.showText(value);
            stream.endText();
        }

        private List<String> wrap(String value, float width, float fontSize)
                throws IOException {
            String normalized = Objects.toString(value, "")
                    .replace('\r', ' ')
                    .replace('\n', ' ');
            if (normalized.isEmpty()) {
                return List.of("");
            }
            List<String> lines = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            float currentWidth = 0;
            for (int index = 0; index < normalized.length();) {
                int codePoint = normalized.codePointAt(index);
                String character = new String(Character.toChars(codePoint));
                float characterWidth = textWidth(character, fontSize);
                if (!current.isEmpty() && currentWidth + characterWidth > width) {
                    lines.add(current.toString());
                    current.setLength(0);
                    currentWidth = 0;
                }
                current.append(character);
                currentWidth += characterWidth;
                index += Character.charCount(codePoint);
            }
            if (!current.isEmpty()) {
                lines.add(current.toString());
            }
            return lines;
        }

        private float textWidth(CharSequence value, float fontSize) throws IOException {
            return font.getStringWidth(value.toString()) / 1000f * fontSize;
        }

        private List<Float> columnWidths(int count) {
            float width = (page.getMediaBox().getWidth() - 2 * MARGIN) / count;
            List<Float> widths = new ArrayList<>(count);
            for (int column = 0; column < count; column++) {
                widths.add(width);
            }
            return widths;
        }

        @Override
        public void close() throws IOException {
            closePage();
        }
    }

}
