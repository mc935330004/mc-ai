package org.example.ai.agent.business.report;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.extractor.XSSFExcelExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.example.ai.agent.business.report.LogicalReportAssembler.ColumnDefinition;
import org.example.ai.agent.business.report.LogicalReportAssembler.MetricDefinition;
import org.example.ai.agent.business.report.LogicalReportAssembler.ReportInput;
import org.example.ai.agent.business.report.LogicalReportAssembler.TableInput;
import org.example.ai.agent.business.report.model.LogicalReportDocument;
import org.example.ai.agent.business.report.model.LogicalReportDocument.SectionState;
import org.example.ai.agent.business.report.model.LogicalReportDocument.SourceDisclosure;
import org.example.ai.agent.business.report.model.LogicalReportDocument.StatusSection;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class ReportRendererContractTest {

    private static final Path NOTO_SANS_SC = resolvePdfFont();
    private static final OffsetDateTime QUERY_TIME = OffsetDateTime.parse(
            "2026-09-02T10:15:30+08:00"
    );

    @Test
    void reportRendererKeepsTheMinimalThreeMethodContract() {
        Set<String> signatures = Arrays.stream(ReportRenderer.class.getDeclaredMethods())
                .map(this::signature)
                .collect(Collectors.toSet());

        assertThat(signatures).containsExactlyInAnyOrder(
                "String format()",
                "String mimeType()",
                "byte[] render(LogicalReportDocument)"
        );
    }

    @Test
    void assemblerAppliesExportPolicyBeforeBuildingAnImmutableDocument() {
        ReportInput input = contractInput(attendanceRows(4));

        LogicalReportDocument document = assembler().assemble(input);

        assertThatThrownBy(() -> input.metricFacts().put(
                "attendance.rate", new BigDecimal("1")
        )).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> input.tables().get(0).rawRows().get(0).put(
                "attendance.hours", 999
        )).isInstanceOf(UnsupportedOperationException.class);

        assertThat(document.metrics())
                .extracting(LogicalReportDocument.Metric::label)
                .containsExactly("出勤率");
        assertThat(document.metrics().get(0).value())
                .isEqualTo(new BigDecimal("96.5"));
        assertThat(document.tables().get(0).columns())
                .containsExactly("日期", "状态", "工时");
        assertThat(document.tables().get(0).rows().get(0).cells())
                .containsExactly("2026-09-01", "正常", new BigDecimal("8.0"));
        assertThat(document.toString())
                .doesNotContain("薪资绝密", "内部备注", "未知原始事实");
        assertThatThrownBy(() -> document.metrics().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> document.tables().get(0).rows().get(0).cells().add("泄漏"))
                .isInstanceOf(UnsupportedOperationException.class);

        String fileName = document.safeFileName("xlsx");
        assertThat(fileName)
                .endsWith(".xlsx")
                .doesNotContain("..", "/", "\\", ":", "*", "?", "\u0000");
        assertThat(fileName.length()).isLessThanOrEqualTo(128);
    }

    @Test
    void threeFormatsShareContentMagicHeadersAndStructuralContracts() throws Exception {
        LogicalReportDocument document = assembler().assemble(contractInput(attendanceRows(4)));
        List<ReportRenderer> renderers = List.of(
                new XlsxReportRenderer(),
                new DocxReportRenderer(),
                new PdfReportRenderer(NOTO_SANS_SC)
        );

        for (ReportRenderer renderer : renderers) {
            byte[] bytes = renderer.render(document);
            assertThat(bytes).isNotEmpty();
            assertMagic(renderer.format(), bytes);
            assertThat(renderer.mimeType()).isNotBlank();
            assertThat(document.safeFileName(renderer.format().toLowerCase()))
                    .endsWith("." + renderer.format().toLowerCase());

            String text = extract(renderer.format(), bytes);
            assertCommonContent(text);
            assertThat(text).doesNotContain("薪资绝密", "内部备注", "未知原始事实");
        }

        byte[] xlsx = renderers.get(0).render(document);
        try (XSSFWorkbook workbook = (XSSFWorkbook) WorkbookFactory.create(
                new ByteArrayInputStream(xlsx)
        )) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("summary");
            assertThat(workbook.getSheetName(1)).isEqualTo("detail");
            assertThat(workbook.getSheet("detail").getPaneInformation().isFreezePane()).isTrue();
            assertThat(workbook.getSheet("detail").getColumnWidth(0))
                    .isBetween(12 * 256, 50 * 256);
            assertThat(findNumericCell(workbook, 96.5d).getCellStyle().getDataFormatString())
                    .isEqualTo("0.##########");
            assertThat(findNumericCell(workbook, 8.0d).getCellStyle().getDataFormatString())
                    .isEqualTo("0");
        }

        byte[] docx = renderers.get(1).render(document);
        try (XWPFDocument word = new XWPFDocument(new ByteArrayInputStream(docx))) {
            assertThat(word.getHeaderList()).isNotEmpty();
            assertThat(word.getFooterList()).isNotEmpty();
            assertThat(word.getTables()).isNotEmpty();
            assertThat(word.getParagraphs())
                    .anySatisfy(paragraph -> assertThat(paragraph.getStyle()).isEqualTo("Title"));
            assertThat(word.getParagraphs().stream()
                    .flatMap(paragraph -> paragraph.getRuns().stream())
                    .anyMatch(run -> !run.getCTR().getBrList().isEmpty()
                            && run.getCTR().getBrList().stream()
                            .anyMatch(br -> "page".equalsIgnoreCase(br.getType().toString()))))
                    .isTrue();
        }

        byte[] pdf = renderers.get(2).render(document);
        try (PDDocument parsed = Loader.loadPDF(pdf)) {
            List<PDFont> fonts = new ArrayList<>();
            parsed.getPages().forEach(page -> page.getResources().getFontNames()
                    .forEach(name -> {
                        try {
                            fonts.add(page.getResources().getFont(name));
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    }));
            assertThat(fonts)
                    .isNotEmpty()
                    .allMatch(PDFont::isEmbedded)
                    .anyMatch(font -> font.getName().matches("[A-Z]{6}\\+.+"));
        }
    }

    @Test
    void xlsxUsesTextWhenExcelCannotRepresentANumberExactly() throws Exception {
        long largeLong = 9_007_199_254_740_993L;
        BigDecimal preciseDecimal = new BigDecimal("123456789012345.678901");
        ReportInput base = contractInput(attendanceRows(4));
        Map<String, Object> facts = new LinkedHashMap<>(base.metricFacts());
        facts.put("precision.long", largeLong);
        facts.put("precision.decimal", preciseDecimal);
        List<FieldPolicy> policies = new ArrayList<>(base.metricPolicies());
        policies.add(policy("precision.long", true));
        policies.add(policy("precision.decimal", true));
        List<MetricDefinition> definitions = new ArrayList<>(base.metricDefinitions());
        definitions.add(new MetricDefinition("precision.long", "精确长整数", null));
        definitions.add(new MetricDefinition("precision.decimal", "精确小数", null));
        ReportInput input = new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), facts, policies, definitions, base.tables(),
                base.statusSections(), base.complete()
        );
        LogicalReportDocument document = assembler().assemble(input);

        for (ReportRenderer renderer : List.of(
                new XlsxReportRenderer(),
                new DocxReportRenderer(),
                new PdfReportRenderer(NOTO_SANS_SC)
        )) {
            assertThat(extract(renderer.format(), renderer.render(document)))
                    .contains(Long.toString(largeLong))
                    .contains(preciseDecimal.toPlainString());
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(
                new XlsxReportRenderer().render(document)
        ))) {
            assertThat(findTextCell(workbook, Long.toString(largeLong)).getCellType())
                    .isEqualTo(CellType.STRING);
            assertThat(findTextCell(workbook, preciseDecimal.toPlainString()).getCellType())
                    .isEqualTo(CellType.STRING);
            assertThat(findNumericCell(workbook, 96.5d).getCellStyle().getDataFormatString())
                    .isEqualTo("0.##########");
            assertThat(findNumericCell(workbook, 8.0d).getCellStyle().getDataFormatString())
                    .isEqualTo("0");
        }
    }

    @Test
    void sanitizedSafeScalarsRemainAvailableAcrossFormats() throws Exception {
        UUID uuid = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Instant instant = Instant.parse("2026-09-02T02:15:30Z");
        ReportInput base = contractInput(attendanceRows(4));
        Map<String, Object> metricFacts = Map.of("summary.value", "原始敏感指标");
        TableInput scalarTable = new TableInput(
                "安全标量",
                List.of(
                        new ColumnDefinition("scalar.uuid", "UUID"),
                        new ColumnDefinition("scalar.instant", "时间")
                ),
                List.of(Map.of("scalar.uuid", uuid, "scalar.instant", instant)),
                List.of(policy("scalar.uuid", true), policy("scalar.instant", true))
        );
        ReportInput input = new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), metricFacts,
                List.of(policy("summary.value", true, "SUMMARY_ONLY")),
                List.of(new MetricDefinition("summary.value", "汇总指标", null)),
                List.of(scalarTable), base.statusSections(), base.complete()
        );
        LogicalReportDocument document = assembler().assemble(input);

        assertThatThrownBy(() -> new LogicalReportDocument.Metric(
                "非法指标", List.of("不允许容器"), null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("metric.value只允许不可变安全标量");
        assertThatThrownBy(() -> new LogicalReportDocument.TableRow(List.of(new Object())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("table.cell只允许不可变安全标量");

        for (ReportRenderer renderer : List.of(
                new XlsxReportRenderer(),
                new DocxReportRenderer(),
                new PdfReportRenderer(NOTO_SANS_SC)
        )) {
            assertThat(extract(renderer.format(), renderer.render(document)))
                    .contains(BusinessFactSanitizer.SUMMARY_ONLY_VALUE)
                    .contains(uuid.toString())
                    .contains(instant.toString())
                    .doesNotContain("原始敏感指标");
        }
    }

    @Test
    void reportInputRejectsGlobalRowBudgetBeforeProcessingFacts() {
        ReportInput base = contractInput(attendanceRows(4));
        TableInput template = base.tables().get(0);
        TableInput first = new TableInput(
                "明细一", template.columns(), attendanceRows(6_000),
                template.fieldPolicies()
        );
        TableInput second = new TableInput(
                "明细二", template.columns(), attendanceRows(5_000),
                template.fieldPolicies()
        );

        assertThatThrownBy(() -> new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), base.metricFacts(), base.metricPolicies(),
                base.metricDefinitions(), List.of(first, second),
                base.statusSections(), base.complete()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("报告总行数最多为10000");
    }

    @Test
    void nonExportableFactsAreDiscardedBeforeFreezing() {
        ReportInput base = contractInput(attendanceRows(4));
        TableInput table = new TableInput(
                "导出明细",
                List.of(new ColumnDefinition("visible.value", "可导出值")),
                List.of(Map.of(
                        "visible.value", "保留",
                        "hidden.value", new Object()
                )),
                List.of(
                        policy("visible.value", true),
                        policy("hidden.value", false)
                )
        );

        ReportInput input = new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), Map.of("hidden.metric", new Object()),
                List.of(policy("hidden.metric", false)),
                List.of(new MetricDefinition("hidden.metric", "隐藏指标", null)),
                List.of(table), base.statusSections(), base.complete()
        );
        LogicalReportDocument document = assembler().assemble(input);

        assertThat(document.metrics()).isEmpty();
        assertThat(document.tables().get(0).rows().get(0).cells()).containsExactly("保留");
    }

    @Test
    void nonExportableTextDoesNotConsumeTheReportBudget() {
        ReportInput base = contractInput(attendanceRows(4));
        String hiddenText = "密".repeat(8_000_001);

        assertThatCode(() -> new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), Map.of("hidden.metric", hiddenText),
                List.of(policy("hidden.metric", false)),
                List.of(new MetricDefinition("hidden.metric", "隐藏指标", null)),
                List.of(), base.statusSections(), base.complete()
        )).doesNotThrowAnyException();
    }

    @Test
    void reportInputRejectsGlobalCellBudget() {
        Map<String, Object> wideRow = new LinkedHashMap<>();
        List<FieldPolicy> policies = new ArrayList<>();
        for (int index = 0; index < 161; index++) {
            String factCode = "wide." + index;
            wideRow.put(factCode, index);
            policies.add(policy(factCode, true));
        }
        TableInput table = new TableInput(
                "宽表", List.of(new ColumnDefinition("wide.0", "值")),
                Collections.nCopies(2_000, wideRow), policies
        );
        ReportInput base = contractInput(attendanceRows(4));

        assertThatThrownBy(() -> new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), Map.of(), List.of(), List.of(),
                List.of(table), base.statusSections(), base.complete()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("报告总单元格数最多为320000");
    }

    @Test
    void reportInputRejectsGlobalTextBudget() {
        String maximumCellText = "字".repeat(4_000);
        TableInput table = new TableInput(
                "长文本", List.of(new ColumnDefinition("text.value", "值")),
                Collections.nCopies(2_001, Map.of("text.value", maximumCellText)),
                List.of(policy("text.value", true))
        );
        ReportInput base = contractInput(attendanceRows(4));

        assertThatThrownBy(() -> new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), Map.of(), List.of(), List.of(),
                List.of(table), base.statusSections(), base.complete()
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("报告文本字符总数最多为8000000");
    }

    @Test
    void reportInputRejectsOverDeepFactsDuringBudgetValidation() {
        ReportInput base = contractInput(attendanceRows(4));
        Object nested = "底层";
        for (int depth = 0; depth < 66; depth++) {
            List<Object> parent = new ArrayList<>();
            parent.add(nested);
            nested = parent;
        }
        Object overDeep = nested;

        assertThatThrownBy(() -> new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), Map.of("deep.value", overDeep),
                List.of(policy("deep.value", true)),
                List.of(new MetricDefinition("deep.value", "深层指标", null)),
                List.of(), base.statusSections(), base.complete()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("报告事实嵌套深度不能超过64");
    }

    @Test
    void reportInputRejectsCyclicFactsDuringBudgetValidation() {
        ReportInput base = contractInput(attendanceRows(4));
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);

        assertThatThrownBy(() -> new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), Map.of("cycle.value", cyclic),
                List.of(policy("cycle.value", true)),
                List.of(new MetricDefinition("cycle.value", "循环指标", null)),
                List.of(), base.statusSections(), base.complete()
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("报告事实存在循环引用");
    }

    @Test
    void reportInputDropsUnknownFactsBeforeDefensiveCopy() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("attendance.date", "2026-09-01");
        row.put("unknown.object", new Object());
        ReportInput base = contractInput(attendanceRows(4));
        TableInput template = base.tables().get(0);

        assertThatCode(() -> new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), base.metricFacts(), base.metricPolicies(),
                base.metricDefinitions(), List.of(new TableInput(
                        template.title(), template.columns(), List.of(row),
                        template.fieldPolicies()
                )), base.statusSections(), base.complete()
        )).doesNotThrowAnyException();
    }

    @Test
    void safeFileNameHonorsUtf8AndWindowsUtf16ComponentLimits() {
        ReportInput base = contractInput(attendanceRows(4));
        ReportInput input = new ReportInput(
                base.title(), "项目😀".repeat(100), base.queryTime(), base.sources(),
                base.associationLabels(), base.metricFacts(), base.metricPolicies(),
                base.metricDefinitions(), base.tables(), base.statusSections(),
                base.complete()
        );

        String fileName = assembler().assemble(input).safeFileName("pdf");

        assertThat(fileName.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(255);
        assertThat(fileName.length()).isLessThanOrEqualTo(255);
        assertThat(fileName).endsWith(".pdf").doesNotContain("�");
    }

    @Test
    void overriddenEnumToStringIsRenderedByStableNameAcrossFormats() throws Exception {
        ReportInput base = contractInput(attendanceRows(4));
        ReportInput input = new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), Map.of("enum.value", OverriddenLabel.APPROVED),
                List.of(policy("enum.value", true)),
                List.of(new MetricDefinition("enum.value", "汇总指标", null)),
                List.of(), base.statusSections(), base.complete()
        );
        LogicalReportDocument document = assembler().assemble(input);

        for (ReportRenderer renderer : List.of(
                new XlsxReportRenderer(),
                new DocxReportRenderer(),
                new PdfReportRenderer(NOTO_SANS_SC)
        )) {
            assertThat(extract(renderer.format(), renderer.render(document)))
                    .contains("APPROVED")
                    .doesNotContain("覆写文本");
        }
    }

    @Test
    void bundledPdfFontCarriesItsLicenseAndSourceNotice() throws Exception {
        ClassLoader loader = ReportRendererContractTest.class.getClassLoader();
        assertThat(loader.getResource("fonts/NotoSansSC-TestSubset.ttf")).isNotNull();
        try (var license = loader.getResourceAsStream("fonts/OFL-1.1.txt");
             var source = loader.getResourceAsStream("fonts/README.txt")) {
            assertThat(license).isNotNull();
            assertThat(source).isNotNull();
            assertThat(new String(license.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("SIL OPEN FONT LICENSE Version 1.1");
            assertThat(new String(source.readAllBytes(), StandardCharsets.UTF_8))
                    .contains("Noto Sans SC", "SIL Open Font License 1.1");
        }
    }

    @Test
    void rendersOnlyFixedSafeMessagesForTerminalSectionStates() throws Exception {
        ReportInput base = contractInput(attendanceRows(4));
        ReportInput input = new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), base.metricFacts(), base.metricPolicies(),
                base.metricDefinitions(), base.tables(),
                List.of(
                        new StatusSection("薪资章节", SectionState.DENIED),
                        new StatusSection("成本章节", SectionState.FAILED),
                        new StatusSection("合同章节", SectionState.TIMEOUT)
                ),
                false
        );
        LogicalReportDocument document = assembler().assemble(input);

        for (ReportRenderer renderer : List.of(
                new XlsxReportRenderer(),
                new DocxReportRenderer(),
                new PdfReportRenderer(NOTO_SANS_SC)
        )) {
            String text = extract(renderer.format(), renderer.render(document));
            assertThat(text)
                    .contains("因权限不足未纳入")
                    .contains("数据查询失败，章节未纳入")
                    .contains("数据查询超时，章节未纳入");
        }
    }

    @Test
    void pdfRepeatsTableHeaderAcrossPages() throws Exception {
        LogicalReportDocument document = assembler().assemble(
                contractInput(attendanceRows(120))
        );
        byte[] pdf = new PdfReportRenderer(NOTO_SANS_SC).render(document);

        try (PDDocument parsed = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(parsed);
            assertThat(parsed.getNumberOfPages()).isGreaterThan(2);
            assertThat(count(text, "日期")).isGreaterThan(1);
            assertThat(count(text, "状态")).isGreaterThan(1);
        }
    }

    @Test
    void pdfWrapsAnOversizedRowAcrossPagesWithoutChangingTheFact() throws Exception {
        String alphabet = "甲乙丙丁戊己庚辛壬癸";
        String longValue = alphabet.repeat(350);
        ReportInput base = contractInput(attendanceRows(4));
        TableInput longTable = new TableInput(
                "超长明细",
                List.of(
                        new ColumnDefinition("long.value", "超长标识"),
                        new ColumnDefinition("stable.one", "固定列一"),
                        new ColumnDefinition("stable.two", "固定列二"),
                        new ColumnDefinition("stable.three", "固定列三")
                ),
                List.of(Map.of(
                        "long.value", longValue,
                        "stable.one", "固定值一",
                        "stable.two", "固定值二",
                        "stable.three", "固定值三"
                )),
                List.of(
                        policy("long.value", true),
                        policy("stable.one", true),
                        policy("stable.two", true),
                        policy("stable.three", true)
                )
        );
        ReportInput input = new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), base.metricFacts(), base.metricPolicies(),
                base.metricDefinitions(), List.of(longTable), base.statusSections(), false
        );

        byte[] pdf = new PdfReportRenderer(NOTO_SANS_SC).render(assembler().assemble(input));

        try (PDDocument parsed = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(parsed);
            String extractedFact = text.codePoints()
                    .filter(codePoint -> alphabet.indexOf(codePoint) >= 0)
                    .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                    .toString();
            assertThat(parsed.getNumberOfPages()).isGreaterThan(2);
            assertThat(count(text, "超长标识")).isGreaterThan(1);
            assertThat(text).doesNotContain("…");
            assertThat(extractedFact).isEqualTo(longValue);
        }
    }

    @Test
    void pdfRejectsAHeaderTooHighToLeaveRoomForData() {
        List<ColumnDefinition> columns = new ArrayList<>();
        List<FieldPolicy> policies = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        String longHeader = "超高中文列名".repeat(33);
        for (int index = 0; index < 32; index++) {
            String factCode = "oversized.header." + index;
            columns.add(new ColumnDefinition(factCode, longHeader));
            policies.add(policy(factCode, true));
            row.put(factCode, "安全值");
        }
        ReportInput base = contractInput(attendanceRows(4));
        TableInput oversizedHeaderTable = new TableInput(
                "超高表头边界", columns, List.of(row), policies
        );
        ReportInput input = new ReportInput(
                base.title(), base.fileStem(), base.queryTime(), base.sources(),
                base.associationLabels(), base.metricFacts(), base.metricPolicies(),
                base.metricDefinitions(), List.of(oversizedHeaderTable),
                base.statusSections(), base.complete()
        );
        LogicalReportDocument document = assembler().assemble(input);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> new PdfReportRenderer(NOTO_SANS_SC).render(document))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("PDF表头过高，无法分页")
        );
    }

    @Test
    void pdfValidatesConfiguredFontOnlyWhenRendering() {
        PdfReportRenderer renderer = new PdfReportRenderer(null);

        assertThatThrownBy(() -> renderer.render(
                assembler().assemble(contractInput(attendanceRows(4)))
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("中文字体")
                .hasMessageContaining("配置");
    }

    private LogicalReportAssembler assembler() {
        return new LogicalReportAssembler(
                new BusinessFactSanitizer(new ReportDatasetValidator())
        );
    }

    private ReportInput contractInput(List<Map<String, Object>> attendanceRows) {
        Map<String, Object> metricFacts = new LinkedHashMap<>();
        metricFacts.put("attendance.rate", new BigDecimal("96.5"));
        metricFacts.put("salary.secret", "薪资绝密");
        metricFacts.put("unknown.raw", "未知原始事实");

        List<FieldPolicy> metricPolicies = List.of(
                policy("attendance.rate", true),
                policy("salary.secret", false)
        );
        List<FieldPolicy> tablePolicies = List.of(
                policy("attendance.date", true),
                policy("attendance.status", true),
                policy("attendance.hours", true),
                policy("attendance.internalNote", false)
        );
        TableInput attendance = new TableInput(
                "考勤明细",
                List.of(
                        new ColumnDefinition("attendance.date", "日期"),
                        new ColumnDefinition("attendance.status", "状态"),
                        new ColumnDefinition("attendance.hours", "工时"),
                        new ColumnDefinition("attendance.internalNote", "内部备注")
                ),
                attendanceRows,
                tablePolicies
        );
        return new ReportInput(
                "项目考勤报告",
                "../项目/考勤:\u0000Q3*?",
                QUERY_TIME,
                List.of(new SourceDisclosure("考勤数据集", "HR_ATTENDANCE")),
                List.of("关联项目：星河工程"),
                metricFacts,
                metricPolicies,
                List.of(
                        new MetricDefinition("attendance.rate", "出勤率", "%"),
                        new MetricDefinition("salary.secret", "薪资", "元"),
                        new MetricDefinition("unknown.raw", "未知指标", null)
                ),
                List.of(attendance),
                List.of(new StatusSection("薪资章节", SectionState.DENIED)),
                false
        );
    }

    private List<Map<String, Object>> attendanceRows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("attendance.date", "2026-09-" + String.format("%02d", index + 1));
            row.put("attendance.status", index == 1 ? "迟到" : "正常");
            row.put("attendance.hours", new BigDecimal(index == 1 ? "7.5" : "8.0"));
            row.put("attendance.internalNote", "内部备注");
            row.put("unknown.raw", "未知原始事实");
            rows.add(row);
        }
        return rows;
    }

    private FieldPolicy policy(String factCode, boolean exportable) {
        return policy(factCode, exportable, "NONE");
    }

    private FieldPolicy policy(String factCode, boolean exportable, String maskStrategy) {
        return new FieldPolicy(
                factCode, "STRING", false, false, exportable, false, maskStrategy, "REPORT"
        );
    }

    private String signature(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(","));
        return method.getReturnType().getSimpleName()
                + " " + method.getName() + "(" + parameters + ")";
    }

    private void assertMagic(String format, byte[] bytes) {
        if ("PDF".equals(format)) {
            assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII))
                    .isEqualTo("%PDF-");
            return;
        }
        assertThat(Arrays.copyOf(bytes, 4))
                .containsExactly(0x50, 0x4b, 0x03, 0x04);
    }

    private String extract(String format, byte[] bytes) throws Exception {
        return switch (format) {
            case "XLSX" -> {
                try (XSSFWorkbook workbook = new XSSFWorkbook(
                        new ByteArrayInputStream(bytes)
                ); XSSFExcelExtractor extractor = new XSSFExcelExtractor(workbook)) {
                    yield extractor.getText();
                }
            }
            case "DOCX" -> {
                try (XWPFDocument document = new XWPFDocument(
                        new ByteArrayInputStream(bytes)
                ); XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                    yield extractor.getText();
                }
            }
            case "PDF" -> {
                try (PDDocument document = Loader.loadPDF(bytes)) {
                    yield new PDFTextStripper().getText(document);
                }
            }
            default -> throw new IllegalArgumentException("未知格式：" + format);
        };
    }

    private void assertCommonContent(String text) {
        assertThat(text)
                .contains("项目考勤报告")
                .contains("2026-09-02T10:15:30+08:00")
                .contains("考勤数据集")
                .contains("HR_ATTENDANCE")
                .contains("关联项目：星河工程")
                .contains("出勤率")
                .contains("96.5")
                .contains("考勤明细")
                .contains("日期")
                .contains("2026-09-01")
                .contains("因权限不足未纳入")
                .contains("数据不完整");
    }

    private int count(String source, String expected) {
        int count = 0;
        int fromIndex = 0;
        while ((fromIndex = source.indexOf(expected, fromIndex)) >= 0) {
            count++;
            fromIndex += expected.length();
        }
        return count;
    }

    private org.apache.poi.ss.usermodel.Cell findNumericCell(
            XSSFWorkbook workbook,
            double expected) {
        for (org.apache.poi.ss.usermodel.Sheet sheet : workbook) {
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                    if (cell.getCellType() == CellType.NUMERIC
                            && cell.getNumericCellValue() == expected) {
                        return cell;
                    }
                }
            }
        }
        throw new AssertionError("未找到数字单元格：" + expected);
    }

    private org.apache.poi.ss.usermodel.Cell findTextCell(
            XSSFWorkbook workbook,
            String expected) {
        for (org.apache.poi.ss.usermodel.Sheet sheet : workbook) {
            for (org.apache.poi.ss.usermodel.Row row : sheet) {
                for (org.apache.poi.ss.usermodel.Cell cell : row) {
                    if (cell.getCellType() == CellType.STRING
                            && expected.equals(cell.getStringCellValue())) {
                        return cell;
                    }
                }
            }
        }
        throw new AssertionError("未找到文本单元格：" + expected);
    }

    /** Noto Sans SC使用SIL OFL 1.1；JVM属性可覆盖classpath内的测试子集。 */
    private static Path resolvePdfFont() {
        String configured = System.getProperty("test.pdf.font-path");
        Path candidate = configured == null || configured.isBlank()
                ? bundledPdfFont()
                : Path.of(configured);
        assertThat(Files.isRegularFile(candidate) && Files.isReadable(candidate))
                .as("PDF合同测试需要Noto Sans SC或兼容中文TTF/OTF；"
                        + "CI请设置 -Dtest.pdf.font-path=<font-file>")
                .isTrue();
        return candidate.toAbsolutePath().normalize();
    }

    private static Path bundledPdfFont() {
        var resource = ReportRendererContractTest.class.getClassLoader()
                .getResource("fonts/NotoSansSC-TestSubset.ttf");
        assertThat(resource).as("缺少classpath测试字体").isNotNull();
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException exception) {
            throw new AssertionError("classpath测试字体路径无效", exception);
        }
    }

    private enum OverriddenLabel {
        APPROVED;

        @Override
        public String toString() {
            return "覆写文本";
        }
    }
}
