package org.example.ai.agent.business.answer;

import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.panorama.model.IssueMatchStatus;
import org.example.ai.agent.business.panorama.model.ProjectIssueResult;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.chat.protocol.block.*;
import org.example.ai.agent.chat.protocol.response.AiResponse;
import org.example.ai.agent.chat.protocol.response.ResponseContext;
import org.example.ai.agent.chat.protocol.response.ResponseMeta;
import org.example.ai.agent.chat.protocol.response.ResponseReference;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;
import org.example.ai.agent.common.enums.protocol.Tone;
import org.example.ai.agent.common.enums.protocol.ValueType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 先编排可信业务区块，再按需追加模型说明。
 */
@Service
public class DeterministicBusinessAnswerComposer {

    private static final int STATUS_ORDER = 10;
    private static final int METRICS_ORDER = 20;
    private static final int WARNINGS_ORDER = 30;
    private static final int CALLOUT_ORDER = 40;
    private static final int TABLE_ORDER = 50;
    private static final int TEXT_ORDER = 90;
    private static final int ARTIFACT_ORDER = 100;
    private static final int MAX_TABLES = 10;
    private static final int MAX_TABLE_ROWS = 10;

    private final BusinessAnswerModelService modelService;

    public DeterministicBusinessAnswerComposer(BusinessAnswerModelService modelService) {
        this.modelService = Objects.requireNonNull(modelService, "modelService不能为空");
    }

    /**
     * 生成一次不可变CHAT回答。
     */
    public AiResponse compose(ComposeCommand command) {
        Objects.requireNonNull(command, "业务回答命令不能为空");

        List<ResponseBlock> blocks = new ArrayList<>();
        addStatusBlock(blocks, command.datasets());
        addMetricsBlock(blocks, command.datasets(), command.metricDefinitions());
        addIssueBlocks(blocks, command.context(), command.issues());
        addTableBlocks(blocks, command.datasets(), command.tableDefinitions());

        boolean dataComplete = isDataComplete(command);
        boolean modelFailed = false;
        if (command.narrativeRequired()) {
            try {
                String text = modelService.generate(toModelRequest(command));
                blocks.add(new TextBlock(
                        "business_narrative", "业务说明", TEXT_ORDER,
                        BlockStatus.READY, BlockSource.AI, text
                ));
            } catch (RuntimeException ignored) {
                modelFailed = true;
            }
        }

        if (command.artifact() != null) {
            blocks.add(withArtifactOrder(command.artifact()));
        }
        validateUniqueBlockIds(blocks);

        ResponseStatus status = responseStatus(
                dataComplete,
                modelFailed,
                !blocks.isEmpty()
        );
        return new AiResponse(
                AiResponse.CURRENT_SCHEMA_VERSION,
                command.responseId(),
                command.runId(),
                command.conversationId(),
                PresentationMode.CHAT,
                status,
                dataComplete,
                command.context(),
                blocks,
                command.references(),
                command.meta()
        );
    }

    private void addStatusBlock(
            List<ResponseBlock> blocks,
            List<DatasetAnswerInput> datasets) {
        if (datasets.isEmpty()) {
            return;
        }
        List<StatusListBlock.StatusItem> items = datasets.stream()
                .map(dataset -> new StatusListBlock.StatusItem(
                        dataset.datasetCode(),
                        dataset.label(),
                        dataset.status().name(),
                        statusTone(dataset.status()),
                        dataset.safeMessage()
                ))
                .toList();
        blocks.add(new StatusListBlock(
                "dataset_status", "数据状态", STATUS_ORDER,
                BlockStatus.READY, BlockSource.BUSINESS, items
        ));
    }

    private void addMetricsBlock(
            List<ResponseBlock> blocks,
            List<DatasetAnswerInput> datasets,
            List<MetricDefinition> definitions) {
        List<DisplayValue> items = new ArrayList<>();
        for (MetricDefinition definition : definitions) {
            DatasetAnswerInput dataset = findDataset(datasets, definition.datasetCode());
            if (dataset == null || !dataset.displayFacts().containsKey(definition.factCode())) {
                continue;
            }
            Object value = scalarValue(dataset.displayFacts().get(definition.factCode()));
            items.add(new DisplayValue(
                    definition.factCode(),
                    definition.label(),
                    value,
                    displayText(value),
                    definition.valueType(),
                    definition.unit(),
                    definition.tone(),
                    List.of()
            ));
        }
        if (!items.isEmpty()) {
            blocks.add(new MetricsBlock(
                    "business_metrics", "核心指标", METRICS_ORDER,
                    BlockStatus.READY, BlockSource.BUSINESS, items
            ));
        }
    }

    private void addIssueBlocks(
            List<ResponseBlock> blocks,
            ResponseContext context,
            List<ProjectIssueResult> issues) {
        List<WarningsBlock.WarningItem> warnings = issues.stream()
                .filter(issue -> issue.status() == IssueMatchStatus.MATCHED)
                .map(issue -> new WarningsBlock.WarningItem(
                        normalize(issue.ruleCode()),
                        context.subjectId(),
                        "业务风险",
                        normalize(issue.message()),
                        issueTone(issue.severity()),
                        issue.evidenceFactCodes()
                ))
                .toList();
        if (!warnings.isEmpty()) {
            blocks.add(new WarningsBlock(
                    "business_warnings", "风险提示", WARNINGS_ORDER,
                    BlockStatus.READY, BlockSource.RULE, warnings
            ));
        }

        String unknownMessage = issues.stream()
                .filter(issue -> issue.status() == IssueMatchStatus.UNKNOWN)
                .map(ProjectIssueResult::message)
                .map(DeterministicBusinessAnswerComposer::normalize)
                .filter(message -> !message.isBlank())
                .distinct()
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
        if (!unknownMessage.isBlank()) {
            blocks.add(new CalloutBlock(
                    "data_completeness", "数据完整性", CALLOUT_ORDER,
                    BlockStatus.READY, BlockSource.RULE,
                    Tone.WARNING, unknownMessage
            ));
        }
    }

    private void addTableBlocks(
            List<ResponseBlock> blocks,
            List<DatasetAnswerInput> datasets,
            List<TableDefinition> definitions) {
        int tableIndex = 0;
        for (TableDefinition definition : definitions) {
            if (tableIndex >= MAX_TABLES) {
                break;
            }
            DatasetAnswerInput dataset = findDataset(datasets, definition.datasetCode());
            if (dataset == null) {
                continue;
            }
            Object value = dataset.displayFacts().get(definition.factCode());
            if (!(value instanceof List<?> sourceRows)) {
                continue;
            }
            blocks.add(toTableBlock(definition, sourceRows, tableIndex));
            tableIndex++;
        }
    }

    /**
     * 将安全展示事实转换成通用表格区块。
     */
    private TableBlock toTableBlock(
            TableDefinition definition,
            List<?> sourceRows,
            int tableIndex
    ) {
        List<TableBlock.Column> columns =
                definition.columns().stream()
                        .map(column ->
                                new TableBlock.Column(
                                        column.factCode(),
                                        column.label(),
                                        column.valueType(),
                                        column.unit()
                                )
                        )
                        .toList();

        List<TableBlock.Row> rows = new ArrayList<>();

        for (int index = 0; index < sourceRows.size() && rows.size() < MAX_TABLE_ROWS; index++) {
            Object rawRow = sourceRows.get(index);
            if (!(rawRow instanceof Map<?, ?> sourceRow)) {
                continue;
            }
            List<DisplayValue> cells = definition.columns().stream().map(column -> tableCell(column, sourceRow.get(column.factCode()))).toList();
            rows.add(new TableBlock.Row(definition.id() + "_" + index, cells,
                    rowAction(definition.rowAction(), sourceRow)
            ));
        }

        boolean explicitPaging = definition.total() != null;

        long total = explicitPaging ? definition.total() : sourceRows.size();

        boolean totalKnown = explicitPaging ? definition.totalKnown() : true;

        boolean hasMore = explicitPaging
                ? definition.hasMore()
                  || sourceRows.size() > rows.size()
                : sourceRows.size() > rows.size();

        int pageNumber = explicitPaging
                ? definition.pageNumber()
                : 1;

        int pageSize = explicitPaging
                ? definition.pageSize()
                : Math.max(rows.size(), 1);

        return new TableBlock(
                definition.id(),
                definition.title(),
                TABLE_ORDER + tableIndex,
                BlockStatus.READY,
                BlockSource.BUSINESS,
                columns,
                rows,
                total,
                hasMore,
                totalKnown,
                pageNumber,
                pageSize
        );
    }
    private DisplayValue tableCell(ColumnDefinition column, Object raw) {
        Object value = scalarValue(raw);
        return new DisplayValue(
                column.factCode(), column.label(), value,
                displayText(value),
                column.valueType(), column.unit(), column.tone(), List.of()
        );
    }
    private BusinessAnswerModelService.ModelRequest toModelRequest(ComposeCommand command) {
        List<BusinessAnswerModelService.ModelDatasetInput> datasets = command.datasets().stream()
                .map(dataset -> new BusinessAnswerModelService.ModelDatasetInput(
                        dataset.datasetCode(), dataset.label(), dataset.modelFacts()
                ))
                .toList();
        return new BusinessAnswerModelService.ModelRequest(
                command.runId(), command.conversationId(), command.userId(),
                command.modelCode(), command.question(), datasets
        );
    }

    /**
     * 只调整产物展示顺序，冻结时间和内容版本必须原样保留。
     */
    private ArtifactBlock withArtifactOrder(ArtifactBlock artifact) {
        return new ArtifactBlock(
                artifact.id(), artifact.title(), ARTIFACT_ORDER,
                artifact.status(), artifact.source(), artifact.taskId(),
                artifact.format(), artifact.fileName(), artifact.taskStatus(),
                artifact.expiresAt(), artifact.frozenAt(), artifact.contentVersion(),
                artifact.dataComplete(), artifact.safeMessage()
        );
    }

    private boolean isDataComplete(ComposeCommand command) {
        if (!command.dataComplete()) {
            return false;
        }
        if (command.datasets().stream().anyMatch(dataset -> !dataset.dataComplete())) {
            return false;
        }
        return command.artifact() == null || command.artifact().dataComplete();
    }

    private ResponseStatus responseStatus(
            boolean dataComplete,
            boolean modelFailed,
            boolean hasVisibleContent) {
        if (!hasVisibleContent) {
            return ResponseStatus.FAILED;
        }
        if (!dataComplete || modelFailed) {
            return ResponseStatus.PARTIAL;
        }
        return ResponseStatus.COMPLETED;
    }

    /**
     * 重复ID会破坏前端累计和SSE revision，必须显式拒绝。
     */
    private void validateUniqueBlockIds(List<ResponseBlock> blocks) {
        HashSet<String> ids = new HashSet<>();
        for (ResponseBlock block : blocks) {
            if (!ids.add(block.id())) {
                throw new IllegalArgumentException(
                        "Block id重复：" + block.id()
                );
            }
        }
    }

    private DatasetAnswerInput findDataset(
            List<DatasetAnswerInput> datasets,
            String datasetCode) {
        return datasets.stream()
                .filter(dataset -> dataset.datasetCode().equals(datasetCode))
                .findFirst()
                .orElse(null);
    }

    private Object scalarValue(Object value) {
        if (value == null || value instanceof String
                || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return null;
    }

    private String displayText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        if (value instanceof Boolean flag) {
            return flag.toString();
        }
        throw new IllegalArgumentException("展示值不是JSON标量");
    }

    private Tone statusTone(DatasetExecutionStatus status) {
        return switch (status) {
            case SUCCESS -> Tone.SUCCESS;
            case EMPTY -> Tone.MUTED;
            case PENDING, RUNNING -> Tone.INFO;
            case DENIED, FAILED, TIMEOUT -> Tone.DANGER;
        };
    }

    private Tone issueTone(String severity) {
        return switch (normalize(severity).toUpperCase(Locale.ROOT)) {
            case "DANGER", "ERROR", "HIGH", "CRITICAL" -> Tone.DANGER;
            case "INFO" -> Tone.INFO;
            case "SUCCESS" -> Tone.SUCCESS;
            case "MUTED" -> Tone.MUTED;
            default -> Tone.WARNING;
        };
    }

    /**
     * 编排输入只允许脱敏事实和显式展示定义。
     */
    public record ComposeCommand(
            String responseId,
            String runId,
            String conversationId,
            ResponseContext context,
            boolean dataComplete,
            List<DatasetAnswerInput> datasets,
            List<MetricDefinition> metricDefinitions,
            List<TableDefinition> tableDefinitions,
            List<ProjectIssueResult> issues,
            ArtifactBlock artifact,
            boolean narrativeRequired,
            String question,
            String userId,
            String modelCode,
            List<ResponseReference> references,
            ResponseMeta meta) {

        public ComposeCommand {
            responseId = requireText(responseId, "回答responseId不能为空");
            runId = requireText(runId, "回答runId不能为空");
            conversationId = requireText(conversationId, "回答conversationId不能为空");
            context = Objects.requireNonNull(context, "回答context不能为空");
            datasets = copy(datasets);
            metricDefinitions = copy(metricDefinitions);
            tableDefinitions = copy(tableDefinitions);
            issues = copy(issues);
            question = normalize(question);
            userId = normalize(userId);
            modelCode = normalize(modelCode);
            references = copy(references);
            meta = meta == null ? ResponseMeta.empty() : meta;
        }
    }

    /**
     * 单个数据集的安全展示和模型事实通道。
     */
    public record DatasetAnswerInput(
            String datasetCode,
            String label,
            DatasetExecutionStatus status,
            boolean dataComplete,
            Map<String, Object> displayFacts,
            Map<String, Object> modelFacts,
            String safeMessage) {

        public DatasetAnswerInput {
            datasetCode = requireText(datasetCode, "数据集code不能为空");
            label = normalize(label);
            status = status == null ? DatasetExecutionStatus.FAILED : status;
            if (dataComplete
                    && status != DatasetExecutionStatus.SUCCESS
                    && status != DatasetExecutionStatus.EMPTY) {
                throw new IllegalArgumentException(
                        "dataComplete=true仅允许SUCCESS或EMPTY状态"
                );
            }
            displayFacts = immutableMap(displayFacts);
            modelFacts = immutableMap(modelFacts);
            safeMessage = safeMessage == null || safeMessage.isBlank()
                    ? status.name()
                    : safeMessage.trim();
        }
    }

    /**
     * 指标只通过字段编码读取displayFacts。
     */
    public record MetricDefinition(
            String datasetCode,
            String factCode,
            String label,
            ValueType valueType,
            String unit,
            Tone tone) {

        public MetricDefinition {
            datasetCode = requireText(datasetCode, "指标datasetCode不能为空");
            factCode = requireText(factCode, "指标factCode不能为空");
            label = normalize(label);
            valueType = valueType == null ? ValueType.TEXT : valueType;
            unit = normalize(unit);
            tone = tone == null ? Tone.DEFAULT : tone;
        }
    }

    /**
     * 表格生成定义。
     */
    public record TableDefinition(
            String id,
            String title,
            String datasetCode,
            String factCode,
            List<ColumnDefinition> columns,
            int pageNumber,
            int pageSize,
            Long total,
            boolean totalKnown,
            boolean hasMore,
            RowActionDefinition rowAction
    ) {

        public TableDefinition {
            id = requireText(
                    id,
                    "表格 id 不能为空"
            );

            title = normalize(title);

            datasetCode = requireText(
                    datasetCode,
                    "表格 datasetCode 不能为空"
            );

            factCode = requireText(
                    factCode,
                    "表格 factCode 不能为空"
            );

            columns = copy(columns);

            if (columns.isEmpty()) {
                throw new IllegalArgumentException(
                        "表格列不能为空"
                );
            }

            if (pageNumber < 1) {
                throw new IllegalArgumentException(
                        "表格 pageNumber 必须大于 0"
                );
            }

            if (pageSize < 1) {
                throw new IllegalArgumentException(
                        "表格 pageSize 必须大于 0"
                );
            }

            if (total != null && total < 0) {
                throw new IllegalArgumentException(
                        "表格 total 不能小于 0"
                );
            }

            if (!totalKnown
                    && total != null
                    && total != 0) {
                throw new IllegalArgumentException(
                        "未知总数时 total 必须为 0"
                );
            }
        }

        /**
         * 兼容原有普通表格定义。
         */
        public TableDefinition(
                String id,
                String title,
                String datasetCode,
                String factCode,
                List<ColumnDefinition> columns
        ) {
            this(
                    id,
                    title,
                    datasetCode,
                    factCode,
                    columns,
                    1,
                    MAX_TABLE_ROWS,
                    null,
                    true,
                    false,
                    null
            );
        }
    }

    /**
     * 表格行操作定义。
     */
    public record RowActionDefinition(String type, String label, String selectionTokenFactCode) {
        public RowActionDefinition {
            type = requireText(
                    type,
                    "行操作类型不能为空"
            );
            label = requireText(
                    label,
                    "行操作名称不能为空"
            );
            selectionTokenFactCode = requireText(
                    selectionTokenFactCode,
                    "选择令牌事实编码不能为空"
            );
        }
    }

    /**
     * 表格列同样只读取已声明字段。
     */
    public record ColumnDefinition(
            String factCode,
            String label,
            ValueType valueType,
            String unit,
            Tone tone) {

        public ColumnDefinition {
            factCode = requireText(factCode, "表格列factCode不能为空");
            label = normalize(label);
            valueType = valueType == null ? ValueType.TEXT : valueType;
            unit = normalize(unit);
            tone = tone == null ? Tone.DEFAULT : tone;
        }
    }

    private static <T> List<T> copy(List<T> source) {
        return source == null ? List.of() : List.copyOf(source);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Object frozen = ReportDatasetValidator.freezeSafeValue(
                source == null ? Map.of() : source
        );
        validateMapKeys(frozen);
        return castMap(frozen);
    }

    private static void validateMapKeys(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = (String) entry.getKey();
                if (key.isBlank()) {
                    throw new IllegalArgumentException("安全值Map key不能为空");
                }
                validateMapKeys(entry.getValue());
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(DeterministicBusinessAnswerComposer::validateMapKeys);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 使用候选行中的服务端凭证构建安全操作。
     */
    private TableBlock.Action rowAction(
            RowActionDefinition definition,
            Map<?, ?> sourceRow) {
        if (definition == null) {
            return null;
        }

        Object tokenValue = sourceRow.get(
                definition.selectionTokenFactCode()
        );

        if (!(tokenValue instanceof String selectionToken)
                || selectionToken.isBlank()) {
            throw new IllegalArgumentException(
                    "声明了表格行操作，但候选行缺少 selectionToken"
            );
        }

        return new TableBlock.Action(
                definition.type(),
                definition.label(),
                selectionToken
        );
    }
}
