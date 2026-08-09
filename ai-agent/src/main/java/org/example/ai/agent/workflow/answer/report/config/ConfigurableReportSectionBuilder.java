package org.example.ai.agent.workflow.answer.report.config;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.formatter.FactValueFormatter;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.example.ai.agent.common.enums.ReportSectionType;
import org.example.ai.agent.graph.model.ReportFieldBindingSpec;
import org.example.ai.agent.graph.model.report.ReportSectionSpec;
import org.example.ai.agent.tool.FieldMeta;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 根据已发布报告定义生成通用 ReportSchema 区块。
 *
 * 本类不查询业务接口、不调用大模型、不生成页面代码。
 */
@Component
@RequiredArgsConstructor
public class ConfigurableReportSectionBuilder {

    private static final int MAX_TREE_DEPTH = 10;
    private static final int MAX_ROW_COUNT = 5000;

    private final ReportValueReader valueReader;
    private final FactValueFormatter valueFormatter;

    public List<ReportSchemaVO.Section> build(ResolvedReportDefinition resolved,JsonNode safeResult) {

        List<ReportSchemaVO.Section> sections =new ArrayList<>();

        for (ReportSectionSpec section : resolved.definition().sections()) {
            sections.add(buildSection(section,resolved,safeResult));
        }

        return List.copyOf(sections);
    }

    private ReportSchemaVO.Section buildSection(ReportSectionSpec section,ResolvedReportDefinition resolved,
                                                JsonNode safeResult) {

        return switch (section.type()) {
            case KEY_VALUE, METRICS ->
                    buildItemSection(
                            section,
                            resolved,
                            safeResult
                    );
            case TABLE, TREE_TABLE ->
                    buildTableSection(
                            section,
                            resolved,
                            safeResult
                    );
        };
    }

    private ReportSchemaVO.Section buildItemSection(
            ReportSectionSpec section,
            ResolvedReportDefinition resolved,
            JsonNode safeResult) {

        List<ReportSchemaVO.Item> items =
                new ArrayList<>();

        for (ReportFieldBindingSpec binding :
                section.fields()) {

            FieldDictionary dictionary =
                    resolved.requireField(
                            binding.fieldId()
                    );

            JsonNode value =
                    valueReader.readScalar(
                            safeResult,
                            binding.sourcePath()
                    );

            items.add(
                    new ReportSchemaVO.Item(
                            binding.key(),
                            resolveLabel(
                                    dictionary,
                                    binding.key()
                            ),
                            formatValue(
                                    value,
                                    dictionary
                            ),
                            resolveDataType(dictionary)
                    )
            );
        }

        return new ReportSchemaVO.Section(
                section.type().name(),
                section.title(),
                items,
                List.of(),
                List.of()
        );
    }

    /**
     * 构建普通表格、嵌套树表或平铺转树表。
     */
    private ReportSchemaVO.Section buildTableSection(
            ReportSectionSpec section,
            ResolvedReportDefinition resolved,
            JsonNode safeResult) {

        List<ReportSchemaVO.Column> columns =
                buildColumns(section, resolved);

        List<JsonNode> sourceRows =valueReader.readMany(safeResult,section.rowPath());

        RowCounter counter = new RowCounter();

        List<Map<String, Object>> rows =
                isFlatTreeSection(section)
                        ? buildFlatTreeRows(
                        sourceRows,
                        section,
                        resolved,
                        counter
                ) : buildNestedRows(
                        sourceRows,
                        section,
                        resolved,
                        counter
                );

        return new ReportSchemaVO.Section(
                section.type().name(),
                section.title(),
                List.of(),
                columns,
                rows
        );
    }
    /**
     * 判断当前区块是否使用父子主键关系构建树。
     */
    private boolean isFlatTreeSection(
            ReportSectionSpec section) {

        return section.type() == ReportSectionType.TREE_TABLE
                && StringUtils.hasText(
                section.parentKeyPath()
        );
    }

    /**
     * 构建普通表格或后台已经嵌套完成的树形表格。
     */
    private List<Map<String, Object>> buildNestedRows(
            List<JsonNode> sourceRows,
            ReportSectionSpec section,
            ResolvedReportDefinition resolved,
            RowCounter counter) {

        List<Map<String, Object>> rows =
                new ArrayList<>();

        for (JsonNode sourceRow : sourceRows) {
            rows.add(
                    buildRow(
                            sourceRow,
                            section,
                            resolved,
                            0,
                            counter
                    )
            );
        }

        return rows;
    }

    /**
     * 将带有主键和父主键的平铺数据转换为树。
     */
    private List<Map<String, Object>> buildFlatTreeRows(
            List<JsonNode> sourceRows,
            ReportSectionSpec section,
            ResolvedReportDefinition resolved,
            RowCounter counter) {

        Map<String, FlatTreeRow> rowsByKey =
                new LinkedHashMap<>();

        for (JsonNode sourceRow : sourceRows) {
            FlatTreeRow treeRow = createFlatTreeRow(
                    sourceRow,
                    section,
                    resolved,
                    counter
            );

            FlatTreeRow duplicate = rowsByKey.putIfAbsent(
                    treeRow.rowKey(),
                    treeRow
            );

            if (duplicate != null) {
                throw new IllegalStateException(
                        "树形报告存在重复 rowKey："
                                + treeRow.rowKey()
                );
            }
        }

        for (FlatTreeRow treeRow : rowsByKey.values()) {
            validateFlatTreeBranch(
                    treeRow,
                    rowsByKey,
                    section
            );
        }

        List<Map<String, Object>> roots =
                new ArrayList<>();

        for (FlatTreeRow treeRow : rowsByKey.values()) {
            if (isRootParent(
                    treeRow.parentKey(),
                    section.rootParentValue())) {

                roots.add(treeRow.values());
                continue;
            }

            FlatTreeRow parent =
                    rowsByKey.get(treeRow.parentKey());

            parent.addChild(treeRow.values());
        }

        return roots;
    }

    /**
     * 将一条业务记录转换为待组装的树节点。
     */
    private FlatTreeRow createFlatTreeRow(
            JsonNode sourceRow,
            ReportSectionSpec section,
            ResolvedReportDefinition resolved,
            RowCounter counter) {

        if (sourceRow == null || !sourceRow.isObject()) {
            throw new IllegalStateException(
                    "报告行数据必须是对象"
            );
        }
        counter.increment();
        Map<String, Object> values =buildFieldValues(
                        sourceRow,
                        section,
                        resolved
                );

        String rowKey = addTreeMetadata(
                sourceRow,
                section,
                values
        );

        String parentKey = readOptionalTreeKey(
                sourceRow,
                section.parentKeyPath()
        );

        return new FlatTreeRow(
                rowKey,
                parentKey,
                values
        );
    }

    /**
     * 检查父节点是否存在、是否循环以及树深是否超限。
     */
    private void validateFlatTreeBranch( FlatTreeRow start,
            Map<String, FlatTreeRow> rowsByKey,
            ReportSectionSpec section) {

        Set<String> visited = new HashSet<>();
        FlatTreeRow current = start;
        int depth = 0;

        while (!isRootParent(
                current.parentKey(),
                section.rootParentValue())) {

            if (!visited.add(current.rowKey())) {
                throw new IllegalStateException(
                        "树形报告存在循环父子关系："
                                + start.rowKey()
                );
            }

            FlatTreeRow parent =rowsByKey.get(current.parentKey());

            if (parent == null) {
                throw new IllegalStateException(
                        "树形报告找不到父节点："
                                + current.parentKey()
                );
            }

            depth++;

            if (depth > MAX_TREE_DEPTH) {
                throw new IllegalStateException(
                        "报告树形数据层级超过限制"
                );
            }

            current = parent;
        }
    }

    /**
     * 空父主键或与配置根值相同的记录视为根节点。
     */
    private boolean isRootParent(
            String parentKey,
            String rootParentValue) {
        if (!StringUtils.hasText(parentKey)) {
            return true;
        }
        return StringUtils.hasText(rootParentValue)
                && rootParentValue.trim().equals(
                parentKey
        );
    }
    private List<ReportSchemaVO.Column> buildColumns(
            ReportSectionSpec section,
            ResolvedReportDefinition resolved) {

        List<ReportSchemaVO.Column> columns =
                new ArrayList<>();

        for (ReportFieldBindingSpec binding :
                section.fields()) {

            FieldDictionary dictionary =
                    resolved.requireField(
                            binding.fieldId()
                    );

            columns.add(
                    new ReportSchemaVO.Column(
                            binding.key(),
                            resolveLabel(
                                    dictionary,
                                    binding.key()
                            ),
                            resolveDataType(dictionary)
                    )
            );
        }

        return List.copyOf(columns);
    }

    private Map<String, Object> buildRow(
            JsonNode sourceRow,
            ReportSectionSpec section,
            ResolvedReportDefinition resolved,
            int depth,
            RowCounter counter) {

        if (sourceRow == null || !sourceRow.isObject()) {
            throw new IllegalStateException(
                    "报告行数据必须是对象"
            );
        }

        if (depth > MAX_TREE_DEPTH) {
            throw new IllegalStateException(
                    "报告树形数据层级超过限制"
            );
        }

        counter.increment();

        Map<String, Object> row =
                buildFieldValues(
                        sourceRow,
                        section,
                        resolved
                );

        if (section.type() != ReportSectionType.TREE_TABLE) {
            return row;
        }
        addTreeMetadata(
                sourceRow,
                section,
                row
        );
        List<JsonNode> childNodes =
                valueReader.readMany(
                        sourceRow,
                        section.childrenPath()
                );

        if (!childNodes.isEmpty()) {
            List<Map<String, Object>> children =
                    new ArrayList<>();

            for (JsonNode childNode : childNodes) {
                children.add(
                        buildRow(
                                childNode,
                                section,
                                resolved,
                                depth + 1,
                                counter
                        )
                );
            }

            row.put("children", children);
        }

        return row;
    }

    /**
     * 为树节点写入前端需要的稳定主键和汇总标记。
     */
    private String addTreeMetadata(
            JsonNode sourceRow,
            ReportSectionSpec section,
            Map<String, Object> row) {

        String rowKey = readRequiredTreeKey(
                sourceRow,
                section.rowKeyPath()
        );

        row.put("rowKey", rowKey);

        if (StringUtils.hasText(
                section.summaryPath())) {

            JsonNode summary =
                    valueReader.readScalar(
                            sourceRow,
                            section.summaryPath()
                    );

            row.put(
                    "summary",
                    summary != null
                            && summary.asBoolean(false)
            );
        }

        return rowKey;
    }

    /**
     * 读取不能为空的树节点主键。
     */
    private String readRequiredTreeKey(JsonNode sourceRow,String path) {

        String value = readOptionalTreeKey(
                sourceRow,
                path
        );
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(
                    "树形报告行缺少稳定 rowKey"
            );
        }
        return value;
    }

    /**
     * 读取可为空的父节点主键。
     */
    private String readOptionalTreeKey(JsonNode sourceRow,String path) {
        JsonNode value =valueReader.readScalar(sourceRow,path);
        if (value == null) {
            return null;
        }
        String text = value.asText().trim();
        return StringUtils.hasText(text) ? text: null;
    }

    private Map<String, Object> buildFieldValues(JsonNode sourceRow, ReportSectionSpec section,
            ResolvedReportDefinition resolved) {

        Map<String, Object> row = new LinkedHashMap<>();

        for (ReportFieldBindingSpec binding : section.fields()) {

            FieldDictionary dictionary =resolved.requireField(binding.fieldId());
            JsonNode value =valueReader.readScalar(sourceRow,binding.sourcePath());

            row.put(binding.key(),formatValue(value, dictionary));
        }

        return row;
    }

    private String formatValue(JsonNode value,FieldDictionary dictionary) {

        return valueFormatter.format(value,toFieldMeta(dictionary));
    }

    private FieldMeta toFieldMeta(FieldDictionary dictionary) {

        return FieldMeta.builder()
                .name(dictionary.getFieldName())
                .cnName(dictionary.getFieldCnName())
                .path(dictionary.getFieldPath())
                .type(dictionary.getFieldType())
                .format(dictionary.getDisplayFormat())
                .meaning(dictionary.getBusinessMeaning())
                .requiredOutput(
                        dictionary.getRequiredOutput()
                )
                .visible(dictionary.getVisible())
                .displayOrder(
                        dictionary.getDisplayOrder()
                )
                .displayGroup(
                        dictionary.getDisplayGroup()
                )
                .nullDisplayText(
                        dictionary.getNullDisplayText()
                )
                .build();
    }

    private String resolveLabel(FieldDictionary dictionary,String fallback) {
        if (StringUtils.hasText(dictionary.getFieldCnName())) {
            return dictionary.getFieldCnName().trim();
        }
        if (StringUtils.hasText(dictionary.getFieldName())) {
            return dictionary.getFieldName().trim();
        }
        return fallback;
    }

    private String resolveDataType(
            FieldDictionary dictionary) {

        if (!StringUtils.hasText(
                dictionary.getFieldType())) {
            return "TEXT";
        }

        return dictionary
                .getFieldType()
                .trim()
                .toUpperCase(Locale.ROOT);
    }
    /**
     * 平铺转树过程中的内部节点。
     */
    private static final class FlatTreeRow {
        private final String rowKey;
        private final String parentKey;
        private final Map<String, Object> values;
        private final List<Map<String, Object>> children = new ArrayList<>();

        private FlatTreeRow(String rowKey,String parentKey,Map<String, Object> values) {
            this.rowKey = rowKey;
            this.parentKey = parentKey;
            this.values = values;
        }
        private String rowKey() {
            return rowKey;
        }
        private String parentKey() {
            return parentKey;
        }
        private Map<String, Object> values() {
            return values;
        }
        private void addChild( Map<String, Object> child) {

            if (children.isEmpty()) {
                values.put("children", children);
            }
            children.add(child);
        }
    }

    private static final class RowCounter {
        private int count;
        private void increment() {
            count++;
            if (count > MAX_ROW_COUNT) {
                throw new IllegalStateException(
                        "报告数据行数超过限制"
                );
            }
        }
    }
}