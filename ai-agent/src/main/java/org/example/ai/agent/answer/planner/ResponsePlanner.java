package org.example.ai.agent.answer.planner;

import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.answer.model.UnifiedFactSet;
import org.example.ai.agent.chat.protocol.block.CalloutBlock;
import org.example.ai.agent.chat.protocol.block.DisplayValue;
import org.example.ai.agent.chat.protocol.block.KeyValueBlock;
import org.example.ai.agent.chat.protocol.block.MetricsBlock;
import org.example.ai.agent.chat.protocol.block.ResponseBlock;
import org.example.ai.agent.chat.protocol.block.TableBlock;
import org.example.ai.agent.chat.protocol.block.WarningsBlock;
import org.example.ai.agent.common.enums.FactSourceType;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.Tone;
import org.example.ai.agent.common.enums.protocol.ValueType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.example.ai.agent.chat.protocol.block.StatusBlock;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;

import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将统一业务事实规划为稳定的回答区块。
 *
 * 不调用大模型，不计算业务金额，
 * 不负责CHAT和REPORT模式路由。
 */
@Component
public class ResponsePlanner {

    private static final int MAX_METRICS = 6;
    private static final int MAX_KEY_VALUES = 12;
    private static final int MAX_TABLE_COLUMNS = 8;
    private static final int MAX_TABLE_ROWS = 10;
    private static final int MAX_TABLE_BLOCKS = 2;
    // 按数组下标排列记录，避免第10条排到第2条前面。
    private static final Pattern ROW_INDEX = Pattern.compile("\\[(\\d+)\\]");
    /**
     * 根据用户问题和可信事实生成回答规划。
     */
    public ResponsePlan plan(
            String question,
            UnifiedFactSet factSet,
            List<String> displayObjectIds,
            List<String> unknownObjectIds) {

        UnifiedFactSet safeFactSet = factSet == null
                ? UnifiedFactSet.empty()
                : factSet;

        List<String> safeObjectIds = displayObjectIds == null
                ? List.of()
                : displayObjectIds.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();

        List<String> safeUnknownIds = unknownObjectIds == null
                ? List.of()
                : unknownObjectIds.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        /*
         * 规则无法完成判定时，本次回答不能标记为数据完整。
         */
        boolean responseDataComplete =safeFactSet.dataComplete() && safeUnknownIds.isEmpty();
        if (safeFactSet.totalCount() == 0) {
            return emptyResult(responseDataComplete);
        }

        List<AnswerFact> visibleFacts =sortFacts(question,safeFactSet.facts().stream().filter(AnswerFact::isUserVisible)
                                .filter(fact -> !fact.isMissing() || fact.isRequiredOutput())
                                .filter(fact -> !isHidden(fact))
                                .toList());
        boolean multiProject = safeObjectIds.size() > 1;

        List<AnswerFact> riskFacts =
                visibleFacts.stream()
                        .filter(fact ->
                                fact.getSourceType()
                                        == FactSourceType.RULE_EVALUATED
                        )
                        .toList();

        List<AnswerFact> businessFacts =
                visibleFacts.stream()
                        .filter(fact ->
                                fact.getSourceType()
                                        != FactSourceType.RULE_EVALUATED
                        )
                        .toList();

        List<ResponseBlock> blocks = new ArrayList<>();

        if (multiProject) {
            blocks.add(buildMultiProjectSummary(safeObjectIds.size()));
        }
        if (!responseDataComplete) {
            blocks.add(buildIncompleteCallout(safeUnknownIds.size()));
        }

        List<AnswerFact> scalarFacts =
                businessFacts.stream().filter(fact -> !StringUtils.hasText(fact.getCollectionKey()))
                        .toList();

        /*
         * 多项目查询不展开单个项目的普通字段，
         * 只保留后端计算或聚合结果。
         */
        if (multiProject) {
            scalarFacts = scalarFacts.stream().filter(this::isCalculatedFact).toList();
        }
        // 缺失状态不生成状态标签，交给后面的普通字段展示。
        List<AnswerFact> calloutFacts = selectDisplayFacts(
                uniqueByField(scalarFacts.stream()
                                .filter(fact -> !fact.isMissing())
                                .filter(this::isCallout)
                                .toList()
                ),
                2
        );
        blocks.addAll(buildCalloutBlocks(calloutFacts));
        List<AnswerFact> statusFacts = selectDisplayFacts(uniqueByField(scalarFacts.stream()
                .filter(fact -> !fact.isMissing()).filter(this::isStatus).toList()), 3);
        blocks.addAll(buildStatusBlocks(statusFacts));

        // 保留全部必答指标，普通指标继续使用原来的默认展示数量。
        List<AnswerFact> metricCandidates = uniqueByField(
                scalarFacts.stream()
                        .filter(fact -> !statusFacts.contains(fact))
                        .filter(fact -> !calloutFacts.contains(fact))
                        .filter(this::isMetric)
                        .toList()
        );

        List<AnswerFact> metricFacts = selectDisplayFacts(metricCandidates, MAX_METRICS);
        if (!metricFacts.isEmpty()) {
            blocks.add(buildMetricsBlock(metricFacts));
        }

        if (!multiProject) {
            List<AnswerFact> keyValueCandidates = uniqueByField(scalarFacts.stream()
                            .filter(fact -> !metricFacts.contains(fact))
                            .filter(fact -> !statusFacts.contains(fact))
                            .filter(fact -> !calloutFacts.contains(fact))
                            .filter(this::isKeyValue)
                            .toList()
            );
            // 非数字的必答取值结果同样不能因数量限制被截掉。
            List<AnswerFact> keyValueFacts = selectDisplayFacts(
                    keyValueCandidates,
                    MAX_KEY_VALUES
            );

            if (!keyValueFacts.isEmpty()) {
                blocks.add(buildKeyValueBlock(keyValueFacts));
            }
        }

        WarningsBlock warningsBlock = buildWarningsBlock(riskFacts);
        if (warningsBlock != null) {
            blocks.add(warningsBlock);
        }

        if (!multiProject) {
            // 当前表格的行数单独计算，不能借用整个查询的项目总数。
            blocks.addAll(buildTableBlocks(businessFacts));
        }

        if (blocks.isEmpty()) {
            blocks.add(buildCompletedCallout(safeFactSet.totalCount()));
        }
        boolean narrativeRequired = requiresNarrative(question, multiProject, warningsBlock != null, !responseDataComplete);
        return new ResponsePlan(blocks, narrativeRequired, responseDataComplete, safeFactSet.totalCount());
    }

    /**
     * 必答字段完整保留，普通字段只补足默认展示数量。
     * 输入已经完成权限过滤、去重和排序，这里不改变原有顺序。
     */
    private List<AnswerFact> selectDisplayFacts(List<AnswerFact> facts, int preferredCount) {
        int requiredCount = (int) facts.stream()
                .filter(AnswerFact::isRequiredOutput)
                .count();
        int optionalRemaining = Math.max(0, preferredCount - requiredCount);
        List<AnswerFact> selected = new ArrayList<>();

        for (AnswerFact fact : facts) {
            if (fact.isRequiredOutput()) {
                selected.add(fact);
            } else if (optionalRemaining > 0) {
                selected.add(fact);
                optionalRemaining--;
            }
        }
        return List.copyOf(selected);
    }

    /**
     * 构建字段配置产生的重点提示。
     */
    private List<ResponseBlock> buildCalloutBlocks(
            List<AnswerFact> facts) {

        if (facts == null
                || facts.isEmpty()) {
            return List.of();
        }

        List<ResponseBlock> blocks =
                new ArrayList<>();

        for (int index = 0;
             index < facts.size();
             index++) {

            AnswerFact fact =
                    facts.get(index);

            blocks.add(
                    new CalloutBlock(
                            "callout_" + safeBlockId(
                                    safeKey(fact)
                            ),
                            safeText(
                                    fact.getLabel(),
                                    "重点信息"
                            ),
                            6 + index,
                            BlockStatus.READY,
                            blockSource(
                                    List.of(fact)
                            ),
                            Tone.INFO,
                            safeText(
                                    fact.getFormattedValue(),
                                    ""
                            )
                    )
            );
        }
        return blocks;
    }

    /**
     * 构建确定性状态区块。
     *
     * 状态文字来自枚举映射，
     * 不由大模型生成。
     */
    private List<ResponseBlock> buildStatusBlocks(
            List<AnswerFact> facts) {

        if (facts == null
                || facts.isEmpty()) {
            return List.of();
        }

        List<ResponseBlock> blocks =
                new ArrayList<>();

        for (int index = 0;
             index < facts.size();
             index++) {

            AnswerFact fact =
                    facts.get(index);

            String rawCode =
                    fact.getRawValue() == null
                            ? ""
                            : String.valueOf(
                            fact.getRawValue()
                    );

            String statusLabel =
                    safeText(
                            fact.getFormattedValue(),
                            rawCode
                    );

            blocks.add(
                    new StatusBlock(
                            "status_" + safeBlockId(
                                    safeKey(fact)
                            ),
                            safeText(
                                    fact.getLabel(),
                                    "状态"
                            ),
                            15 + index,
                            BlockStatus.READY,
                            blockSource(
                                    List.of(fact)
                            ),
                            rawCode,
                            statusLabel,
                            Tone.DEFAULT
                    )
            );
        }

        return blocks;
    }


    /**
     * 没有业务数据时返回固定提示，不调用模型。
     */
    private ResponsePlan emptyResult(boolean dataComplete) {

        String content = dataComplete
                ? "没有查询到符合条件的业务数据。"
                : "本次查询没有获得完整的业务数据，请检查业务接口返回结果。";

        CalloutBlock block = new CalloutBlock("query_empty", "查询结果", 0, BlockStatus.READY, BlockSource.SYSTEM, dataComplete
                        ? Tone.INFO : Tone.WARNING, content);

        return new ResponsePlan(
                List.of(block),
                false,
                dataComplete,
                0
        );
    }

    /**
     * 多项目查询只提示查询范围，
     * 不展开所有项目的全部字段。
     */
    private CalloutBlock buildMultiProjectSummary(
            int projectCount) {

        return new CalloutBlock(
                "multi_project_summary",
                "查询概况",
                0,
                BlockStatus.READY,
                BlockSource.SYSTEM,
                Tone.INFO,
                "共查询到 "
                        + projectCount
                        + " 个项目。为保持回答简洁，"
                        + "当前只展示汇总、风险和数据不完整信息。"
        );
    }

    /**
     * 数据不完整必须明确提示，
     * 禁止让用户误认为结果覆盖了全部数据。
     */
    private CalloutBlock buildIncompleteCallout(
            int unknownCount) {

        String content = unknownCount > 0
                ? "有 " + unknownCount
                + " 个业务对象因关键字段不足无法完成规则判定，"
                + "以下内容只基于当前有效数据。"
                : "部分业务数据没有完整返回，"
                + "以下内容只基于当前有效数据。";

        return new CalloutBlock(
                "data_incomplete",
                "数据完整性提示",
                5,
                BlockStatus.READY,
                BlockSource.SYSTEM,
                Tone.WARNING,
                content
        );
    }

    private MetricsBlock buildMetricsBlock(
            List<AnswerFact> facts) {

        List<DisplayValue> items =
                facts.stream()
                        .map(this::toDisplayValue)
                        .toList();

        return new MetricsBlock(
                "core_metrics",
                "核心指标",
                10,
                BlockStatus.READY,
                blockSource(facts),
                items
        );
    }

    private KeyValueBlock buildKeyValueBlock(
            List<AnswerFact> facts) {

        List<DisplayValue> items =
                facts.stream()
                        .map(this::toDisplayValue)
                        .toList();

        return new KeyValueBlock(
                "basic_information",
                resolveGroupTitle(
                        facts,
                        "基础信息"
                ),
                20,
                BlockStatus.READY,
                blockSource(facts),
                items
        );
    }

    /**
     * 只把风险规则明确命中的事实转成风险区块。
     *
     * UNKNOWN和NOT_MATCHED都不能当成已确认风险。
     */
    private WarningsBlock buildWarningsBlock(
            List<AnswerFact> riskFacts) {

        List<WarningsBlock.WarningItem> warnings =
                new ArrayList<>();

        for (AnswerFact fact : riskFacts) {
            Map<?, ?> riskValue =
                    asMap(fact.getRawValue());

            if (riskValue == null
                    || !"MATCHED".equals(
                    text(riskValue.get("status"))
            )) {
                continue;
            }

            String message =
                    firstText(
                            riskValue.get("reason"),
                            fact.getFormattedValue()
                    );

            warnings.add(new WarningsBlock.WarningItem(
                            safeKey(fact),
                            text(riskValue.get("objectId")),
                            safeText(fact.getLabel(), "风险提示"),
                            message,
                            riskTone(text(riskValue.get("severity"))),
                            List.of()));
        }

        if (warnings.isEmpty()) {
            return null;
        }

        return new WarningsBlock(
                "risk_warnings",
                "风险提示",
                30,
                BlockStatus.READY,
                BlockSource.RULE,
                warnings
        );
    }

    /**
     * 保留必答表组，普通表组补足默认数量。
     */
    private List<ResponseBlock> buildTableBlocks(List<AnswerFact> businessFacts) {
        Map<String, List<AnswerFact>> collections = new LinkedHashMap<>();

        for (AnswerFact fact : businessFacts) {
            if (StringUtils.hasText(fact.getCollectionKey())) {
                collections.computeIfAbsent(
                        fact.getCollectionKey(), ignored -> new ArrayList<>()
                ).add(fact);
            }
        }

        int requiredCount = (int) collections.values().stream()
                .filter(facts -> facts.stream().anyMatch(AnswerFact::isRequiredOutput))
                .count();

        int optionalRemaining = Math.max(0, MAX_TABLE_BLOCKS - requiredCount);
        List<ResponseBlock> blocks = new ArrayList<>();

        for (List<AnswerFact> collectionFacts : collections.values()) {
            boolean required = collectionFacts.stream()
                    .anyMatch(AnswerFact::isRequiredOutput);

            if (!required && optionalRemaining == 0) {
                continue;
            }

            TableBlock table = buildTableBlock(collectionFacts, blocks.size());
            if (table == null) {
                continue;
            }

            blocks.add(table);
            if (!required) {
                optionalRemaining--;
            }
        }

        return blocks;
    }

    /**
     * 首屏只返回10行，其余记录通过同一快照分页读取。
     */
    private TableBlock buildTableBlock(List<AnswerFact> facts, int tableIndex) {
        List<AnswerFact> columnFacts = selectDisplayFacts(
                uniqueByField(facts), MAX_TABLE_COLUMNS
        );

        if (columnFacts.isEmpty()) {
            return null;
        }

        List<TableBlock.Column> columns = columnFacts.stream()
                .map(fact -> new TableBlock.Column(
                        safeKey(fact),
                        safeText(fact.getLabel(), safeKey(fact)),
                        valueType(fact),
                        ""
                ))
                .toList();

        Map<String, List<AnswerFact>> rows = groupTableRows(facts);

        List<TableBlock.Row> firstPage = rows.entrySet().stream()
                .limit(MAX_TABLE_ROWS)
                .map(entry -> new TableBlock.Row(
                        entry.getKey(),
                        buildRowCells(columnFacts, entry.getValue())
                ))
                .toList();

        // 集合身份保持稳定，翻页不能依赖“第几张表”猜测数据来源。
        String tableId = "table_"
                + ContentHashUtils.sha256(facts.get(0).getCollectionKey());

        return new TableBlock(
                tableId,
                resolveGroupTitle(facts, "数据明细"),
                40 + tableIndex,
                BlockStatus.READY,
                blockSource(facts),
                columns,
                firstPage,
                rows.size(),
                rows.size() > firstPage.size()
        );
    }

    /**
     * 首屏和后续分页使用相同的记录顺序。
     */
    private Map<String, List<AnswerFact>> groupTableRows(List<AnswerFact> facts) {
        Map<String, List<AnswerFact>> rows = new TreeMap<>(
                Comparator.comparing((String path) -> rowOrder(path))
                        .thenComparing(Comparator.naturalOrder())
        );

        for (AnswerFact fact : facts) {
            String recordPath = safeText(fact.getRecordPath(), "row_0");
            rows.computeIfAbsent(recordPath, ignored -> new ArrayList<>()).add(fact);
        }

        return rows;
    }

    /**
     * 只处理排序键，不修改真实记录路径。
     */
    private String rowOrder(String path) {
        return ROW_INDEX.matcher(path).replaceAll(match ->
                "[" + "0".repeat(Math.max(0, 10 - match.group(1).length()))
                        + match.group(1) + "]"
        );
    }

    /**
     * 按原表格的集合和列恢复数据，不重新规划表格或扩大字段权限。
     */
    public TableBlock restoreTable(TableBlock preview, UnifiedFactSet factSet) {
        List<String> collections = factSet.facts().stream()
                .map(AnswerFact::getCollectionKey)
                .filter(StringUtils::hasText)
                .distinct()
                .filter(key -> preview.id().equals(
                        "table_" + ContentHashUtils.sha256(key)
                ))
                .toList();

        if (collections.size() != 1) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "结果快照中未找到当前表格的数据集合，请重新查询"
            );
        }

        String collectionKey = collections.get(0);

        List<AnswerFact> facts = factSet.facts().stream()
                .filter(fact -> collectionKey.equals(fact.getCollectionKey()))
                .filter(AnswerFact::isUserVisible)
                .filter(fact -> !isHidden(fact))
                .filter(fact -> !fact.isMissing() || fact.isRequiredOutput())
                .filter(fact -> fact.getSourceType() != FactSourceType.RULE_EVALUATED)
                .toList();

        List<AnswerFact> columnFacts = new ArrayList<>();

        for (TableBlock.Column column : preview.columns()) {
            List<AnswerFact> matches = facts.stream()
                    .filter(fact -> column.key().equals(safeKey(fact)))
                    .toList();

            if (matches.isEmpty()) {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "结果快照未保存字段【" + column.label() + "】，无法继续翻页"
                );
            }

            // 同名字段来自不同路径时，不能随意选择其中一个。
            long identities = matches.stream()
                    .map(fact -> List.of(
                            safeText(fact.getCapabilityCode(), ""),
                            safeText(fact.getFieldPath(), ""),
                            safeText(fact.getFieldName(), "")
                    ))
                    .distinct()
                    .count();

            if (identities != 1) {
                throw new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "字段【" + column.label() + "】存在多个数据来源，无法安全翻页"
                );
            }

            columnFacts.add(matches.get(0));
        }

        List<TableBlock.Row> rows = groupTableRows(facts).entrySet().stream()
                .map(entry -> new TableBlock.Row(
                        entry.getKey(),
                        buildRowCells(columnFacts, entry.getValue())
                ))
                .toList();

        return new TableBlock(
                preview.id(),
                preview.title(),
                preview.order(),
                BlockStatus.READY,
                preview.source(),
                preview.columns(),
                rows,
                rows.size(),
                false
        );
    }

    /**
     * 按列生成单元格，缺失数字显示0，其它类型留空。
     */
    private List<DisplayValue> buildRowCells(List<AnswerFact> columns, List<AnswerFact> rowFacts) {

        List<DisplayValue> cells = new ArrayList<>();

        for (AnswerFact column : columns) {
            AnswerFact valueFact = rowFacts.stream()
                    .filter(fact -> safeKey(fact).equals(safeKey(column)))
                    .findFirst()
                    .orElse(null);

            if (valueFact != null) {
                cells.add(toDisplayValue(valueFact));
                continue;
            }

            // 只按字段类型决定空值，不能用金额格式猜测数据类型。
            String type = normalize(column.getValueType());

            String emptyText = switch (type) {
                case "number", "integer", "int", "long",
                     "float", "double", "decimal", "bigdecimal", "numeric" -> "0";
                default -> "";
            };

            cells.add(new DisplayValue(
                    safeKey(column),
                    safeText(column.getLabel(), safeKey(column)),
                    null,
                    emptyText,
                    valueType(column),
                    "",
                    Tone.DEFAULT,
                    List.of()
            ));
        }
        return cells;
    }

    private CalloutBlock buildCompletedCallout(
            long totalCount) {

        return new CalloutBlock(
                "query_completed",
                "查询结果",
                0,
                BlockStatus.READY,
                BlockSource.SYSTEM,
                Tone.INFO,
                "查询完成，共获得 "
                        + totalCount
                        + " 条有效业务数据。"
        );
    }

    /**
     * 用户关注字段和必答字段优先展示。
     */
    private List<AnswerFact> sortFacts(String question, List<AnswerFact> facts) {
        String normalizedQuestion = normalize(question);
        return facts.stream().sorted( Comparator.<AnswerFact>comparingInt(
                                        fact -> relevance(normalizedQuestion, fact))
                                .thenComparingInt(this::displayOrder)
                                .thenComparing(fact -> safeText(fact.getFactKey(), ""))).toList();
    }

    /**
     * 用户关注、必答字段和高重要字段优先。
     */
    private int relevance(
            String normalizedQuestion,
            AnswerFact fact) {

        if (StringUtils.hasText(
                normalizedQuestion)) {

            String label =
                    normalize(
                            fact.getLabel()
                    );

            String fieldName =
                    normalize(
                            fact.getFieldName()
                    );

            String fieldCode =
                    normalize(
                            fact.getFieldCode()
                    );

            if (StringUtils.hasText(label)
                    && normalizedQuestion.contains(label)) {
                return 0;
            }

            if (StringUtils.hasText(fieldName)
                    && normalizedQuestion.contains(fieldName)) {
                return 0;
            }

            if (StringUtils.hasText(fieldCode)
                    && normalizedQuestion.contains(fieldCode)) {
                return 0;
            }
        }

        if (fact.isRequiredOutput()) {
            return 1;
        }

        if ("HIGH".equalsIgnoreCase(
                fact.getImportance())) {
            return 2;
        }

        if (fact.isSummary()) {
            return 3;
        }

        if ("LOW".equalsIgnoreCase(
                fact.getImportance())) {
            return 5;
        }

        return 4;
    }

    private int displayOrder(AnswerFact fact) {
        return fact.getDisplayOrder() == null
                ? Integer.MAX_VALUE
                : fact.getDisplayOrder();
    }

    private List<AnswerFact> uniqueByField( List<AnswerFact> facts) {

        Map<String, AnswerFact> unique =
                new LinkedHashMap<>();

        for (AnswerFact fact : facts) {
            unique.putIfAbsent(
                    safeKey(fact),
                    fact
            );
        }

        return List.copyOf(
                unique.values()
        );
    }

    /**
     * 判断字段是否进入核心指标区块。
     */
    private boolean isMetric(
            AnswerFact fact) {

        String component =component(fact);

        if ("METRICS".equals(component)) {
            return true;
        }

        if (!"AUTO".equals(component)) {
            return false;
        }

        return isCalculatedFact(fact)
                || fact.isSummary()
                && isNumericFact(fact);
    }

    /**
     * 判断是否为数字、金额或比例字段。
     */
    private boolean isNumericFact( AnswerFact fact) {

        String format = normalize(fact.getDisplayFormat());

        String type =normalize(fact.getValueType());

        return format.contains("amount")
                || format.contains("money")
                || format.contains("percent")
                || format.contains("number")
                || type.equals("number")
                || type.equals("integer")
                || type.equals("int")
                || type.equals("long")
                || type.equals("float")
                || type.equals("double")
                || type.equals("decimal")
                || type.equals("bigdecimal")
                || type.equals("numeric");
    }

    private boolean isHidden(
            AnswerFact fact) {

        return "HIDDEN".equals(
                component(fact)
        );
    }

    private boolean isStatus(
            AnswerFact fact) {

        String component =
                component(fact);

        if ("STATUS".equals(component)) {
            return true;
        }

        if (!"AUTO".equals(component)) {
            return false;
        }

        String format =
                normalize(
                        fact.getDisplayFormat()
                );

        return "status".equals(format)
                || "enum".equals(format);
    }

    private boolean isCallout(
            AnswerFact fact) {

        return "CALLOUT".equals(
                component(fact)
        );
    }

    /**
     * 尚未被其它组件展示的必答字段，保留在基础信息中。
     */
    private boolean isKeyValue(AnswerFact fact) {
        String component = component(fact);
        return fact.isRequiredOutput()
                || "AUTO".equals(component)
                || "KEY_VALUE".equals(component);
    }

    private String component(AnswerFact fact) {
        String component = normalize(fact.getDisplayComponent()).toUpperCase(Locale.ROOT);
        return StringUtils.hasText(component)
                ? component
                : "AUTO";
    }
    private boolean isCalculatedFact(AnswerFact fact) {
        return fact.getSourceType() == FactSourceType.CALCULATED || fact.getSourceType() == FactSourceType.AGGREGATED;
    }

    private DisplayValue toDisplayValue(AnswerFact fact) {

        return new DisplayValue(
                safeKey(fact),
                safeText(fact.getLabel(), safeKey(fact)),
                scalarValue(fact.getRawValue()),
                safeText(fact.getFormattedValue(), ""),
                valueType(fact),
                safeText(fact.getUnit(),""),
                Tone.DEFAULT,
                List.of()
        );
    }

    private ValueType valueType(
            AnswerFact fact) {

        String format =
                normalize(fact.getDisplayFormat());

        String type =
                normalize(fact.getValueType());

        if (format.contains("amount")
                || format.contains("money")) {
            return ValueType.AMOUNT;
        }

        if (format.contains("percent")) {
            return ValueType.PERCENT;
        }

        if (format.contains("datetime")) {
            return ValueType.DATETIME;
        }

        if (format.contains("date")) {
            return ValueType.DATE;
        }

        if (format.contains("status")
                || format.contains("enum")) {
            return ValueType.ENUM;
        }

        if (type.equals("boolean")) {
            return ValueType.BOOLEAN;
        }

        if (isNumericFact(fact)) {
            return ValueType.NUMBER;
        }

        return ValueType.TEXT;
    }

    private BlockSource blockSource(List<AnswerFact> facts) {
        boolean calculationOnly = facts.stream().allMatch(this::isCalculatedFact);
        return calculationOnly
                ? BlockSource.CALCULATION
                : BlockSource.BUSINESS;
    }
    /**
     * 将字段编码转换为安全稳定的Block ID。
     */
    private String safeBlockId( String value) {
        String normalized = StringUtils.hasText(value)
                        ? value.trim()
                        : "field";
        return normalized.replaceAll("[^A-Za-z0-9_-]", "_" );
    }
    private String resolveGroupTitle(List<AnswerFact> facts, String defaultTitle) {
        return facts.stream()
                .map(AnswerFact::getDisplayGroup)
                .filter(StringUtils::hasText)
                .findFirst()
                .map(String::trim)
                .orElse(defaultTitle);
    }

    private boolean requiresNarrative(String question, boolean multiProject,
                                      boolean hasWarnings, boolean dataIncomplete) {

        if (multiProject || hasWarnings || dataIncomplete) {
            return true;
        }

        String normalized =
                normalize(question);

        return containsAny(
                normalized,
                "分析",
                "总结",
                "说明",
                "原因",
                "为什么",
                "建议",
                "风险",
                "异常",
                "不规范"
        );
    }

    private boolean containsAny(String text, String... markers) {

        for (String marker : markers) {
            if (text.contains(normalize(marker))) {
                return true;
            }
        }

        return false;
    }

    private Tone riskTone(String severity) {

        return switch (normalize(severity)) {
            case "high" -> Tone.DANGER;
            case "medium" -> Tone.WARNING;
            case "low" -> Tone.INFO;
            default -> Tone.WARNING;
        };
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    /**
     * Block协议只允许保存标量原始值。
     */
    private Object scalarValue(Object value) {

        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return null;
    }

    private String safeKey(AnswerFact fact) {
        return firstText(fact.getFieldCode(), fact.getFieldName(), fact.getFactKey(), "field");
    }

    private String firstText(Object... values) {
        for (Object value : values) {
            String resolved =text(value);
            if (StringUtils.hasText(resolved)) {
                return resolved;
            }
        }
        return "";
    }

    private String safeText(String value, String defaultValue) {
        return StringUtils.hasText(value)
                ? value.trim()
                : defaultValue;
    }
    private String text( Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll(
                        "[\\s（）()：:，,。；;]+",
                        ""
                );
    }
}