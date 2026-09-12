package org.example.ai.agent.business.report;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.BusinessFactSanitizer;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.model.FieldPolicy;
import org.example.ai.agent.business.model.AssociationType;
import org.example.ai.agent.business.report.LogicalReportAssembler.ColumnDefinition;
import org.example.ai.agent.business.report.LogicalReportAssembler.MetricDefinition;
import org.example.ai.agent.business.report.LogicalReportAssembler.ReportInput;
import org.example.ai.agent.business.report.LogicalReportAssembler.TableInput;
import org.example.ai.agent.business.report.CompositeReportTaskService.ArtifactMetadata;
import org.example.ai.agent.business.report.entity.CompositeReportSection;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.report.mapper.CompositeReportTaskMapper;
import org.example.ai.agent.business.report.model.LogicalReportDocument.SectionState;
import org.example.ai.agent.business.report.model.LogicalReportDocument.SourceDisclosure;
import org.example.ai.agent.business.report.model.LogicalReportDocument.StatusSection;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshotItem;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotItemMapper;
import org.example.ai.agent.business.snapshot.mapper.BusinessSnapshotMapper;
import org.example.ai.agent.common.file.SafeArtifactStorageService;
import org.example.ai.agent.common.file.SafeArtifactStorageService.StoredArtifact;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 生产报告文件处理器。
 *
 * 只消费安全快照的导出事实和当前字段策略，不读取来源系统原始响应；
 * 三个格式共享同一份逻辑报告，避免格式之间统计口径不一致。
 *
 * 安全约束：
 * 1. 章节未配置、缺少安全快照或快照结构不合法时失败关闭，不生成含义不明的内容。
 * 2. 只读取快照的 export 通道；display、model、calculation 通道不进入报告文件。
 * 3. 关联说明与章节披露只使用服务端固定文案，不拼装任何原始错误或业务值。
 */
@Component
public class BusinessReportFileHandler implements CompositeReportWorker.ReportFileHandler {

    /** 直接项目关联的固定标签。 */
    private static final String DIRECT_LABEL = "项目直接关联";
    /** 项目人员期间关联的固定标签。 */
    private static final String CONTEXT_LABEL =
            "项目人员期间关联，仅供上下文参考，不计入项目直接统计";
    /** 项目关联无法确认的固定标签。 */
    private static final String UNKNOWN_LABEL = "部分记录的项目关联无法确认";
    /** 数据源未配置的固定标签。 */
    private static final String UNAVAILABLE_LABEL = "数据源未配置，本章节未纳入统计";

    private static final String EXPORT_CHANNEL = "export";
    private static final String FACTS_ERROR = "报告快照事实结构不合法";
    private static final String SOURCE_REFERENCE = "安全快照导出事实";
    private static final int MAX_SECTIONS = 32;

    private final CompositeReportTaskMapper taskMapper;
    private final BusinessSnapshotMapper snapshotMapper;
    private final BusinessSnapshotItemMapper itemMapper;
    private final ReportDatasetMapper datasetMapper;
    private final ReportDatasetFieldMapper fieldMapper;
    private final SafeArtifactStorageService storage;
    private final ObjectMapper objectMapper;
    private final LogicalReportAssembler assembler;
    private final Map<String, ReportRenderer> renderers;

    @Autowired
    public BusinessReportFileHandler(
            CompositeReportTaskMapper taskMapper,
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ReportDatasetMapper datasetMapper,
            ReportDatasetFieldMapper fieldMapper,
            BusinessFactSanitizer factSanitizer,
            SafeArtifactStorageService storage,
            ObjectMapper objectMapper,
            @Value("${ai.business.composite-report.pdf-font-path:}") String pdfFontPath) {
        this(taskMapper, snapshotMapper, itemMapper, datasetMapper, fieldMapper,
                new LogicalReportAssembler(
                        Objects.requireNonNull(factSanitizer, "factSanitizer不能为空")
                ),
                storage, objectMapper, defaultRenderers(pdfFontPath));
    }

    /** 测试构造器：允许注入捕获型渲染器，断言逻辑报告内容。 */
    BusinessReportFileHandler(
            CompositeReportTaskMapper taskMapper,
            BusinessSnapshotMapper snapshotMapper,
            BusinessSnapshotItemMapper itemMapper,
            ReportDatasetMapper datasetMapper,
            ReportDatasetFieldMapper fieldMapper,
            LogicalReportAssembler assembler,
            SafeArtifactStorageService storage,
            ObjectMapper objectMapper,
            Map<String, ReportRenderer> renderers) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper不能为空");
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper不能为空");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper不能为空");
        this.datasetMapper = Objects.requireNonNull(datasetMapper, "datasetMapper不能为空");
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper不能为空");
        this.assembler = Objects.requireNonNull(assembler, "assembler不能为空");
        this.storage = Objects.requireNonNull(storage, "storage不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
        this.renderers = Map.copyOf(
                Objects.requireNonNull(renderers, "renderers不能为空")
        );
    }

    /** 三个格式共享同一份逻辑报告，避免格式之间口径不一致。 */
    private static Map<String, ReportRenderer> defaultRenderers(String pdfFontPath) {
        Map<String, ReportRenderer> byFormat = new LinkedHashMap<>();
        for (ReportRenderer renderer : List.of(
                new XlsxReportRenderer(),
                new DocxReportRenderer(),
                new PdfReportRenderer(fontPath(pdfFontPath)))) {
            byFormat.put(renderer.format(), renderer);
        }
        return byFormat;
    }

    /** PDF 字体路径由环境变量提供，不允许硬编码本机路径。 */
    private static Path fontPath(String value) {
        return StringUtils.hasText(value) ? Path.of(value.trim()) : null;
    }

    @Override
    public ArtifactMetadata generate(
            String taskId,
            String format,
            String targetPath,
            List<CompositeReportSection> sections) {
        CompositeReportTask task = taskMapper.selectByTaskId(taskId);
        if (task == null) {
            throw new IllegalStateException("报告任务不存在");
        }
        if (sections == null || sections.isEmpty() || sections.size() > MAX_SECTIONS) {
            throw new IllegalStateException("报告章节数量不合法");
        }
        ReportRenderer renderer = renderers.get(
                String.valueOf(format).toUpperCase(Locale.ROOT)
        );
        if (renderer == null) {
            throw new IllegalStateException("报告格式不受支持");
        }
        byte[] content = renderer.render(assembler.assemble(collect(task, sections)));
        try {
            StoredArtifact stored = storage.store(targetPath, content);
            return new ArtifactMetadata(
                    stored.relativePath(), stored.fileName(),
                    renderer.mimeType(), stored.fileSize(), stored.checksum()
            );
        } catch (IOException exception) {
            throw new IllegalStateException("报告文件写入失败", exception);
        }
    }

    /**
     * 把章节安全快照收集为逻辑报告输入。
     *
     * 指标与表格的口径：
     * - export 通道中的标量事实按可导出字段生成指标；
     * - export 通道中的记录列表生成表格，列只取记录中实际出现且可导出的字段，顺序按 displayOrder。
     */
    private ReportInput collect(
            CompositeReportTask task,
            List<CompositeReportSection> sections) {
        Map<String, Object> metricFacts = new LinkedHashMap<>();
        List<FieldPolicy> metricPolicies = new ArrayList<>();
        List<MetricDefinition> metricDefinitions = new ArrayList<>();
        List<TableInput> tables = new ArrayList<>();
        List<StatusSection> statusSections = new ArrayList<>();
        List<SourceDisclosure> sources = new ArrayList<>();
        Set<String> associationLabels = new LinkedHashSet<>();

        for (CompositeReportSection section : ordered(sections)) {
            collectSection(
                    section, metricFacts, metricPolicies, metricDefinitions,
                    tables, statusSections, sources, associationLabels
            );
        }
        if (statusSections.isEmpty()) {
            throw new IllegalStateException("报告没有任何可用章节");
        }
        // 任一章节省略时报告必须标记为不完整，不能声明完整数据。
        boolean complete = Boolean.TRUE.equals(task.getDataComplete())
                && statusSections.stream()
                .allMatch(section -> section.state() == SectionState.SUCCESS);
        return new ReportInput(
                reportTitle(task),
                "report-" + task.getTaskId(),
                OffsetDateTime.now(),
                sources,
                List.copyOf(associationLabels),
                metricFacts,
                metricPolicies,
                metricDefinitions,
                tables,
                statusSections,
                complete
        );
    }

    private void collectSection(
            CompositeReportSection section,
            Map<String, Object> metricFacts,
            List<FieldPolicy> metricPolicies,
            List<MetricDefinition> metricDefinitions,
            List<TableInput> tables,
            List<StatusSection> statusSections,
            List<SourceDisclosure> sources,
            Set<String> associationLabels) {
        String title = sectionTitle(section);
        // 没有启用配置的章节只登记安全状态，不伪造任何字段。
        ReportDataset dataset = datasetMapper.selectEnabledByCode(section.getDatasetCode());
        List<ReportDatasetField> fields = dataset == null || dataset.getId() == null
                ? List.of()
                : datasetFields(dataset.getId());
        if (dataset == null || fields.isEmpty()) {
            statusSections.add(new StatusSection(title, SectionState.DENIED));
            associationLabels.add(UNAVAILABLE_LABEL);
            return;
        }
        sources.add(new SourceDisclosure(datasetLabel(dataset), SOURCE_REFERENCE));

        List<BusinessSnapshotItem> items = StringUtils.hasText(section.getSnapshotId())
                ? snapshotItems(section.getSnapshotId())
                : List.of();
        addAssociationLabels(items, associationLabels);

        BusinessSnapshot snapshot = StringUtils.hasText(section.getSnapshotId())
                ? snapshotMapper.selectById(section.getSnapshotId())
                : null;
        if (snapshot == null || !StringUtils.hasText(snapshot.getFactsJson())) {
            statusSections.add(new StatusSection(title, statusState(section.getStatus())));
            addSectionDisclosure(section, associationLabels);
            return;
        }
        Map<String, Object> exportFacts = exportFacts(readFacts(snapshot.getFactsJson()), items);
        List<FieldPolicy> policies = fields.stream()
                .map(BusinessReportFileHandler::toPolicy)
                .toList();
        for (ReportDatasetField field : fields) {
            if (!Boolean.TRUE.equals(field.getExportable())
                    || !StringUtils.hasText(field.getFactCode())) {
                continue;
            }
            String factCode = field.getFactCode().trim();
            Object value = exportFacts.get(factCode);
            if (value == null || value instanceof BusinessFactSanitizer.MissingValue) {
                continue;
            }
            if (value instanceof List<?> records) {
                TableInput table = table(field, records, fields, policies);
                if (table != null) {
                    tables.add(table);
                }
                continue;
            }
            metricFacts.put(factCode, value);
            metricPolicies.add(toPolicy(field));
            metricDefinitions.add(new MetricDefinition(factCode, fieldLabel(field), null));
        }
        statusSections.add(new StatusSection(title, statusState(section.getStatus())));
        addSectionDisclosure(section, associationLabels);
    }

    private List<CompositeReportSection> ordered(List<CompositeReportSection> sections) {
        List<CompositeReportSection> ordered = new ArrayList<>(sections);
        ordered.sort(Comparator
                .comparing(
                        CompositeReportSection::getDisplayOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())
                )
                .thenComparing(
                        CompositeReportSection::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ));
        return ordered;
    }

    private List<ReportDatasetField> datasetFields(Long datasetId) {
        List<ReportDatasetField> fields = fieldMapper.selectList(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .eq(ReportDatasetField::getDatasetId, datasetId)
                        .orderByAsc(ReportDatasetField::getDisplayOrder, ReportDatasetField::getId)
        );
        return fields == null ? List.of() : fields;
    }

    private List<BusinessSnapshotItem> snapshotItems(String snapshotId) {
        List<BusinessSnapshotItem> items = itemMapper.selectList(
                Wrappers.<BusinessSnapshotItem>lambdaQuery()
                        .eq(BusinessSnapshotItem::getSnapshotId, snapshotId)
                        .orderByAsc(BusinessSnapshotItem::getId)
        );
        return items == null ? List.of() : items;
    }

    /**
     * 只读取直接关联执行项的导出通道。
     *
     * 派生快照按关联项键组织通道，普通快照直接在顶层包含通道；
     * 其他关联类型只形成固定标签，其记录和金额不进入报告。
     */
    private Map<String, Object> exportFacts(
            Map<String, Object> facts,
            List<BusinessSnapshotItem> items) {
        for (BusinessSnapshotItem item : items) {
            if (item == null
                    || !AssociationType.DIRECT.name().equals(item.getAssociationType())
                    || !StringUtils.hasText(item.getItemKey())) {
                continue;
            }
            Object envelope = facts.get(item.getItemKey());
            if (envelope instanceof Map<?, ?> map
                    && map.get(EXPORT_CHANNEL) instanceof Map<?, ?> export) {
                return stringKeyed(export);
            }
        }
        return facts.get(EXPORT_CHANNEL) instanceof Map<?, ?> export
                ? stringKeyed(export)
                : Map.of();
    }

    private void addAssociationLabels(
            List<BusinessSnapshotItem> items,
            Set<String> associationLabels) {
        for (BusinessSnapshotItem item : items) {
            if (item == null || !StringUtils.hasText(item.getAssociationType())) {
                continue;
            }
            int count = item.getTotalCount() == null ? 0 : item.getTotalCount();
            if (count <= 0) {
                continue;
            }
            switch (item.getAssociationType().trim()) {
                case "DIRECT" -> associationLabels.add(DIRECT_LABEL);
                case "PROJECT_PERSON_PERIOD" -> associationLabels.add(CONTEXT_LABEL);
                case "UNKNOWN" -> associationLabels.add(UNKNOWN_LABEL);
                default -> {
                    // 无关记录只保留在快照中，不进入报告。
                }
            }
        }
    }

    /** 章节披露只接受服务端固定文案，其他内容忽略，避免形成侧信道。 */
    private void addSectionDisclosure(
            CompositeReportSection section,
            Set<String> associationLabels) {
        String safeMessage = section.getSafeMessage();
        if (!StringUtils.hasText(safeMessage)) {
            return;
        }
        String message = safeMessage.trim();
        if (BusinessAssistantReportService.isPersonUnavailableMessage(message)) {
            associationLabels.add(message);
        }
    }

    private TableInput table(
            ReportDatasetField factField,
            List<?> records,
            List<ReportDatasetField> fields,
            List<FieldPolicy> policies) {
        List<Map<String, Object>> rows = new ArrayList<>(records.size());
        Set<String> presentCodes = new LinkedHashSet<>();
        for (Object record : records) {
            if (!(record instanceof Map<?, ?> map)) {
                throw new IllegalStateException(FACTS_ERROR);
            }
            Map<String, Object> row = stringKeyed(map);
            rows.add(row);
            presentCodes.addAll(row.keySet());
        }
        List<ColumnDefinition> columns = fields.stream()
                .filter(field -> Boolean.TRUE.equals(field.getExportable())
                        && StringUtils.hasText(field.getFactCode())
                        && presentCodes.contains(field.getFactCode().trim()))
                .map(field -> new ColumnDefinition(field.getFactCode().trim(), fieldLabel(field)))
                .toList();
        if (columns.isEmpty() || rows.isEmpty()) {
            return null;
        }
        return new TableInput(fieldLabel(factField), columns, rows, policies);
    }

    private Map<String, Object> readFacts(String factsJson) {
        try {
            Map<String, Object> facts = objectMapper.readValue(
                    factsJson, new TypeReference<Map<String, Object>>() {
                    }
            );
            return facts == null ? Map.of() : facts;
        } catch (RuntimeException | IOException exception) {
            throw new IllegalStateException(FACTS_ERROR, exception);
        }
    }

    private Map<String, Object> stringKeyed(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    private static FieldPolicy toPolicy(ReportDatasetField field) {
        return new FieldPolicy(
                field.getFactCode(),
                field.getFactType(),
                Boolean.TRUE.equals(field.getCalculable()),
                Boolean.TRUE.equals(field.getDisplayable()),
                Boolean.TRUE.equals(field.getExportable()),
                Boolean.TRUE.equals(field.getModelVisible()),
                field.getMaskStrategy(),
                field.getGrain()
        );
    }

    private static String fieldLabel(ReportDatasetField field) {
        return StringUtils.hasText(field.getFactName())
                ? field.getFactName().trim()
                : field.getFactCode();
    }

    private static String datasetLabel(ReportDataset dataset) {
        return StringUtils.hasText(dataset.getDatasetName())
                ? dataset.getDatasetName().trim()
                : dataset.getDatasetCode();
    }

    private static String sectionTitle(CompositeReportSection section) {
        return StringUtils.hasText(section.getDatasetCode())
                ? section.getDatasetCode().trim()
                : "业务章节";
    }

    /** 标题只由主体类型枚举生成，不拼装主体名称或编码。 */
    private static String reportTitle(CompositeReportTask task) {
        String subjectType = StringUtils.hasText(task.getSubjectType())
                ? task.getSubjectType().trim().toUpperCase(Locale.ROOT)
                : "";
        return switch (subjectType) {
            case "PROJECT" -> "项目业务数据报告";
            case "PERSON" -> "人员业务数据报告";
            case "DEPARTMENT" -> "部门业务数据报告";
            default -> "业务数据报告";
        };
    }

    /** 章节状态只映射为固定安全状态，不携带原始错误。 */
    private static SectionState statusState(String status) {
        String normalized = StringUtils.hasText(status)
                ? status.trim().toUpperCase(Locale.ROOT)
                : "";
        return switch (normalized) {
            case "REUSED", "QUERIED", "SUCCESS" -> SectionState.SUCCESS;
            case "EMPTY" -> SectionState.EMPTY;
            case "DENIED" -> SectionState.DENIED;
            case "TIMEOUT" -> SectionState.TIMEOUT;
            default -> SectionState.FAILED;
        };
    }
}
