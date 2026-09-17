package org.example.ai.agent.business.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.report.CompositeReportTaskService.ArtifactMetadata;
import org.example.ai.agent.business.report.BusinessReportFileHandler.FrozenReport;
import org.example.ai.agent.business.report.entity.CompositeReportSection;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.report.mapper.CompositeReportTaskMapper;
import org.example.ai.agent.business.report.model.LogicalReportDocument;
import org.example.ai.agent.business.report.model.LogicalReportDocument.Metric;
import org.example.ai.agent.business.report.model.LogicalReportDocument.ReportTable;
import org.example.ai.agent.business.report.model.LogicalReportDocument.SectionState;
import org.example.ai.agent.business.report.model.LogicalReportDocument.StatusSection;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshotItem;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotItemMapper;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.common.file.SafeArtifactStorageService;
import org.example.ai.agent.common.file.SafeArtifactStorageService.StoredArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 报告文件处理器契约测试。
 *
 * 只验证安全边界与确定性映射：导出通道、固定关联标签、失败关闭和通道隔离。
 */
class BusinessReportFileHandlerTest {

    private static final String TASK_ID = "task-1";
    private static final String SNAPSHOT_ID = "snap-1";
    private static final String UNAVAILABLE_MESSAGE = "报销数据源尚未配置，本次未纳入统计";
    private static final String CONTEXT_LABEL =
            "项目人员期间关联，仅供上下文参考，不计入项目直接统计";

    @ParameterizedTest
    @ValueSource(strings = {"XLSX", "DOCX", "PDF"})
    void rendersDirectExportFactsWithAssociationLabelsAndDisclosure(String format) {
        Fixture fixture = new Fixture();
        fixture.prepareDataset(
                field("travel_amount", "出差总金额", 1),
                field("travel_records", "出差明细", 2)
        );
        fixture.prepareSnapshot("""
                {"direct":{
                  "calculation":{"travel_amount":10},
                  "display":{"travel_amount":"10"},
                  "export":{"travel_amount":10,
                            "travel_records":[{"travel_amount":10,"start_at":"2026-03-01"}]},
                  "model":{"travel_amount":10}}}
                """);
        fixture.prepareItems(
                item("direct", "DIRECT", 1),
                item("context", "PROJECT_PERSON_PERIOD", 2),
                item("unknown", "UNKNOWN", 1),
                item("unrelated", "UNRELATED", 5)
        );

        String target = "reports/" + TASK_ID + "/report." + format.toLowerCase();
        ArtifactMetadata artifact = fixture.generate(format, target, fixture.sections(UNAVAILABLE_MESSAGE));

        LogicalReportDocument document = fixture.capturedDocument;
        assertThat(document.associationLabels())
                .contains(
                        "项目直接关联",
                        CONTEXT_LABEL,
                        "部分记录的项目关联无法确认",
                        UNAVAILABLE_MESSAGE
                );
        assertThat(document.metrics()).extracting(Metric::label)
                .containsExactly("出差总金额");
        assertThat(document.tables()).extracting(ReportTable::title)
                .containsExactly("出差明细");
        assertThat(document.statusSections()).extracting(StatusSection::state)
                .containsExactly(SectionState.SUCCESS);
        // 章节存在不可用披露时报告必须标记为不完整。
        assertThat(document.complete()).isFalse();
        assertThat(artifact.storagePath()).isEqualTo(target);
        assertThat(artifact.fileName()).isEqualTo("report." + format.toLowerCase());
        assertThat(artifact.fileSize()).isEqualTo(fixture.storedBytes.length);
        assertThat(fixture.storedBytes).isNotEmpty();
    }

    @Test
    void displayAndModelChannelsMustNotBecomeReportFacts() {
        Fixture fixture = new Fixture();
        fixture.prepareDataset(field("secret_amount", "隐藏金额", 1));
        fixture.prepareSnapshot("""
                {"direct":{
                  "calculation":{},
                  "display":{"secret_amount":999},
                  "export":{},
                  "model":{"secret_amount":999}}}
                """);
        fixture.prepareItems(item("direct", "DIRECT", 1));

        fixture.generate("XLSX", "reports/task-1/report.xlsx", fixture.sections(null));

        LogicalReportDocument document = fixture.capturedDocument;
        assertThat(document.metrics()).isEmpty();
        assertThat(document.tables()).isEmpty();
        assertThat(document.statusSections()).extracting(StatusSection::state)
                .containsExactly(SectionState.SUCCESS);
    }

    @Test
    void exportableFactMustRemainAvailableWhenModelVisibilityIsDisabled() {
        Fixture fixture = new Fixture();
        ReportDatasetField exportOnly = field("approved_amount", "审批金额", 1);
        exportOnly.setModelVisible(false);
        fixture.prepareDataset(exportOnly);
        fixture.prepareSnapshot("{\"direct\":{\"export\":{\"approved_amount\":88},\"model\":{}}}");
        fixture.prepareItems(item("direct", "DIRECT", 1));

        fixture.generate("XLSX", "reports/task-1/report.xlsx", fixture.sections(null));

        assertThat(fixture.capturedDocument.metrics()).extracting(Metric::label)
                .containsExactly("审批金额");
    }

    @Test
    void invalidSnapshotFactsMustFailClosed() {
        Fixture fixture = new Fixture();
        fixture.prepareDataset(field("travel_amount", "出差总金额", 1));
        fixture.prepareSnapshot("not-a-json-object");
        fixture.prepareItems(item("direct", "DIRECT", 1));

        assertThatThrownBy(() -> fixture.freeze(fixture.sections(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("报告快照事实结构不合法");
    }

    @Test
    void unconfiguredDatasetMustRegisterDeniedSectionWithoutFabricatingFacts() {
        Fixture fixture = new Fixture();
        when(fixture.datasetMapper.selectEnabledByCode("PERSON_TRAVEL")).thenReturn(null);

        fixture.generate("XLSX", "reports/task-1/report.xlsx", fixture.sections(null));

        LogicalReportDocument document = fixture.capturedDocument;
        assertThat(document.statusSections()).extracting(StatusSection::state)
                .containsExactly(SectionState.DENIED);
        assertThat(document.associationLabels())
                .containsExactly("数据源未配置，本章节未纳入统计");
        assertThat(document.metrics()).isEmpty();
        assertThat(document.complete()).isFalse();
    }

    @Test
    void unsupportedFormatMustBeRejected() {
        Fixture fixture = new Fixture();
        fixture.prepareDataset(field("travel_amount", "出差总金额", 1));
        fixture.prepareSnapshot("{\"export\":{}}");

        assertThatThrownBy(() -> fixture.handler.generate(
                TASK_ID, "CSV", "reports/task-1/report.csv", fixture.sections(null)
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("报告格式不受支持");
    }

    @Test
    void renderMustOnlyReadFrozenDocument() {
        Fixture fixture = new Fixture();
        fixture.prepareDataset(field("travel_amount", "出差总金额", 1));
        fixture.prepareSnapshot("{\"export\":{\"travel_amount\":10}}");
        List<CompositeReportSection> sections = fixture.sections(null);
        fixture.freeze(sections);

        // 冻结完成后清空交互记录，渲染阶段不得再次访问快照或字段配置。
        clearInvocations(fixture.snapshotMapper, fixture.itemMapper,
                fixture.datasetMapper, fixture.fieldMapper);
        fixture.handler.generate(TASK_ID, "XLSX", "reports/task-1/report.xlsx", sections);

        verifyNoInteractions(fixture.snapshotMapper, fixture.itemMapper,
                fixture.datasetMapper, fixture.fieldMapper);
        assertThat(fixture.capturedDocument.metrics()).extracting(Metric::label)
                .containsExactly("出差总金额");
    }

    @Test
    void changedFrozenJsonMustFailContentVersionCheck() {
        Fixture fixture = new Fixture();
        fixture.prepareDataset(field("travel_amount", "出差总金额", 1));
        fixture.prepareSnapshot("{\"export\":{\"travel_amount\":10}}");
        List<CompositeReportSection> sections = fixture.sections(null);
        fixture.freeze(sections);
        fixture.task.setLogicalReportJson("{}");

        assertThatThrownBy(() -> fixture.handler.generate(
                TASK_ID, "XLSX", "reports/task-1/report.xlsx", sections
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("冻结逻辑报告内容版本不一致");
    }

    @Test
    void differentSourceRunMustProduceDifferentContentVersion() {
        Fixture fixture = new Fixture();
        fixture.prepareDataset(field("travel_amount", "出差总金额", 1));
        fixture.prepareSnapshot("{\"export\":{\"travel_amount\":10}}");
        List<CompositeReportSection> sections = fixture.sections(null);

        FrozenReport current = fixture.handler.freeze(fixture.task, sections);
        fixture.task.setSourceRunId("run-2");
        FrozenReport latest = fixture.handler.freeze(fixture.task, sections);

        assertThat(current.logicalReportJson()).isEqualTo(latest.logicalReportJson());
        assertThat(current.contentVersion()).isNotEqualTo(latest.contentVersion());
    }

    private static ReportDatasetField field(String factCode, String factName, int displayOrder) {
        ReportDatasetField field = new ReportDatasetField();
        field.setDatasetId(7L);
        field.setFactCode(factCode);
        field.setFactName(factName);
        field.setFactType("NUMBER");
        field.setCalculable(true);
        field.setDisplayable(true);
        field.setExportable(true);
        field.setModelVisible(true);
        field.setMaskStrategy("NONE");
        field.setGrain("PERSON");
        field.setDisplayOrder(displayOrder);
        return field;
    }

    private static BusinessSnapshotItem item(String itemKey, String associationType, int count) {
        BusinessSnapshotItem item = new BusinessSnapshotItem();
        item.setSnapshotId(SNAPSHOT_ID);
        item.setItemKey(itemKey);
        item.setAssociationType(associationType);
        item.setTotalCount(count);
        return item;
    }

    /** 捕获渲染器：替换真实文件渲染，只记录逻辑报告并返回固定字节。 */
    private static final class CapturingRenderer implements ReportRenderer {

        private final String format;
        private final java.util.function.Consumer<LogicalReportDocument> capture;

        private CapturingRenderer(
                String format,
                java.util.function.Consumer<LogicalReportDocument> capture) {
            this.format = format;
            this.capture = capture;
        }

        @Override
        public String format() {
            return format;
        }

        @Override
        public String mimeType() {
            return "application/octet-stream";
        }

        @Override
        public byte[] render(LogicalReportDocument document) {
            capture.accept(document);
            return ("rendered-" + format).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static final class Fixture {

        private final CompositeReportTaskMapper taskMapper = mock(CompositeReportTaskMapper.class);
        private final BusinessSnapshotMapper snapshotMapper = mock(BusinessSnapshotMapper.class);
        private final BusinessSnapshotItemMapper itemMapper = mock(BusinessSnapshotItemMapper.class);
        private final ReportDatasetMapper datasetMapper = mock(ReportDatasetMapper.class);
        private final ReportDatasetFieldMapper fieldMapper = mock(ReportDatasetFieldMapper.class);
        private final SafeArtifactStorageService storage = mock(SafeArtifactStorageService.class);
        private final Map<String, CapturingRenderer> renderers = new LinkedHashMap<>();
        private final CompositeReportTask task = new CompositeReportTask();

        private final BusinessReportFileHandler handler;
        private LogicalReportDocument capturedDocument;
        private byte[] storedBytes;

        private Fixture() {
            for (String format : List.of("XLSX", "DOCX", "PDF")) {
                renderers.put(format, new CapturingRenderer(format, document -> {
                    capturedDocument = document;
                }));
            }
            task.setTaskId(TASK_ID);
            task.setSourceRunId("run-1");
            task.setFormat("XLSX");
            task.setSubjectType("PERSON");
            task.setDataComplete(false);
            task.setFrozenAt(LocalDateTime.of(2026, 9, 17, 10, 0));
            when(taskMapper.selectByTaskId(TASK_ID)).thenReturn(task);
            when(snapshotMapper.selectById(SNAPSHOT_ID))
                    .thenReturn(snapshot("{\"export\":{}}"));
            when(itemMapper.selectList(any())).thenReturn(List.of());
            when(fieldMapper.selectList(any())).thenReturn(List.of());
            when(datasetMapper.selectEnabledByCode(anyString()))
                    .thenReturn(dataset("PERSON_TRAVEL", "出差数据"));
            try {
                when(storage.store(anyString(), any())).thenAnswer(invocation -> {
                    storedBytes = invocation.getArgument(1);
                    String target = invocation.getArgument(0);
                    return new StoredArtifact(
                            target, target.substring(target.lastIndexOf('/') + 1),
                            storedBytes.length, "a".repeat(64)
                    );
                });
            } catch (Exception exception) {
                throw new IllegalStateException("制品存储模拟失败", exception);
            }
            handler = new BusinessReportFileHandler(
                    taskMapper, snapshotMapper, itemMapper, datasetMapper, fieldMapper,
                    new LogicalReportAssembler(new BusinessFactSanitizer(new ReportDatasetValidator())),
                    storage, new ObjectMapper().findAndRegisterModules(),
                    Map.copyOf(renderers)
            );
        }

        private CapturingRenderer renderer;

        private void prepareDataset(ReportDatasetField... fields) {
            ReportDataset dataset = dataset("PERSON_TRAVEL", "出差数据");
            dataset.setId(7L);
            when(datasetMapper.selectEnabledByCode("PERSON_TRAVEL")).thenReturn(dataset);
            when(fieldMapper.selectList(any())).thenReturn(List.of(fields));
        }

        private void prepareSnapshot(String factsJson) {
            when(snapshotMapper.selectById(SNAPSHOT_ID)).thenReturn(snapshot(factsJson));
        }

        private void prepareItems(BusinessSnapshotItem... items) {
            when(itemMapper.selectList(any())).thenReturn(List.of(items));
        }

        private void freeze(List<CompositeReportSection> sections) {
            FrozenReport frozen = handler.freeze(task, sections);
            task.setContentVersion(frozen.contentVersion());
            task.setLogicalReportJson(frozen.logicalReportJson());
        }

        private ArtifactMetadata generate(String format, String target,
                                          List<CompositeReportSection> sections) {
            freeze(sections);
            return handler.generate(TASK_ID, format, target, sections);
        }

        private List<CompositeReportSection> sections(String safeMessage) {
            CompositeReportSection section = new CompositeReportSection();
            section.setId(1L);
            section.setTaskId(TASK_ID);
            section.setDatasetCode("PERSON_TRAVEL");
            section.setSnapshotId(SNAPSHOT_ID);
            section.setFieldPolicyChecksum("a".repeat(64));
            section.setStatus("REUSED");
            section.setDisplayOrder(1);
            section.setSafeMessage(safeMessage);
            return List.of(section);
        }

        private static ReportDataset dataset(String code, String name) {
            ReportDataset dataset = new ReportDataset();
            dataset.setDatasetCode(code);
            dataset.setDatasetName(name);
            dataset.setDomainCode(code);
            dataset.setEnabled(true);
            dataset.setFieldPolicyChecksum("a".repeat(64));
            return dataset;
        }

        private static BusinessSnapshot snapshot(String factsJson) {
            BusinessSnapshot snapshot = new BusinessSnapshot();
            snapshot.setSnapshotId(SNAPSHOT_ID);
            snapshot.setDatasetCode("PERSON_TRAVEL");
            snapshot.setUserId("user-1");
            snapshot.setSessionId("session-1");
            snapshot.setFactsJson(factsJson);
            snapshot.setFieldPolicyChecksum("a".repeat(64));
            return snapshot;
        }
    }
}
