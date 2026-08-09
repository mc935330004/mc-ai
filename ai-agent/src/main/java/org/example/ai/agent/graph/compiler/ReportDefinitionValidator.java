package org.example.ai.agent.graph.compiler;

import org.example.ai.agent.common.enums.ReportSectionType;
import org.example.ai.agent.graph.model.ReportFieldBindingSpec;
import org.example.ai.agent.graph.model.report.ReportDefinitionSpec;
import org.example.ai.agent.graph.model.report.ReportSectionSpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.example.ai.agent.graph.model.report.ReportFollowUpSpec;

import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 校验工作流报告定义。
 *
 * 路径只允许字段名、点号和数组展开符号，
 * 禁止脚本、过滤器、函数和递归搜索。
 */
@Component
public class ReportDefinitionValidator {

    private static final int MAX_SECTION_COUNT = 20;
    private static final int MAX_FIELD_COUNT = 30;

    private static final Pattern SAFE_PATH = Pattern.compile(
            "^(\\$\\.)?[A-Za-z_][A-Za-z0-9_]*(\\[\\])?"
                    + "(\\.[A-Za-z_][A-Za-z0-9_]*(\\[\\])?)*$"
    );

    private static final Set<String> RESERVED_TREE_KEYS =Set.of("rowKey", "children", "summary");
    private static final int MAX_FOLLOW_UP_PROMPT_LENGTH = 300;
    private static final int MAX_FOLLOW_UP_INPUT_COUNT = 30;
    private static final int MAX_LITERAL_LENGTH = 500;

    private static final String SOURCE_PREFIX = "$source.";
    private static final String SELECTED_PREFIX = "$selected.";
    /**
     * 报告追问当前只支持直接能力和工作流两种目标。
     */
    private static final Set<String> FOLLOW_UP_TARGET_TYPES =Set.of("CAPABILITY", "WORKFLOW");

    /**
     * 能力编码和工作流编码使用相同的安全字符范围。
     */
    private static final Pattern FOLLOW_UP_TARGET_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{1,127}$");

    private static final Pattern INPUT_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");
    /**
     * 返回报告定义中的全部校验错误。
     */
    public List<GraphValidationError> validate(
            ReportDefinitionSpec definition) {

        if (definition == null) {
            return List.of();
        }

        List<GraphValidationError> errors = new ArrayList<>();

        if (definition.reportType() == null) {
            errors.add(error(
                    "REPORT_TYPE_REQUIRED",
                    "root.reportDefinition",
                    "报告类型不能为空"
            ));
        }

        if (!StringUtils.hasText(definition.title())) {
            errors.add(error(
                    "REPORT_TITLE_REQUIRED",
                    "root.reportDefinition",
                    "报告标题不能为空"
            ));
        }
        validateFollowUp(definition.followUp(),errors);
        if (definition.sections().isEmpty()) {
            errors.add(error(
                    "REPORT_SECTION_REQUIRED",
                    "root.reportDefinition",
                    "报告至少需要一个业务区块"
            ));
            return errors;
        }

        if (definition.sections().size() > MAX_SECTION_COUNT) {
            errors.add(error(
                    "REPORT_SECTION_LIMIT",
                    "root.reportDefinition",
                    "报告区块数量不能超过" + MAX_SECTION_COUNT
            ));
        }

        for (int index = 0;
             index < definition.sections().size();
             index++) {

            validateSection(
                    definition.sections().get(index),
                    index,
                    errors
            );
        }

        return List.copyOf(errors);
    }
    /**
     * 校验报告完成后的通用追问配置。
     */
    private void validateFollowUp(
            ReportFollowUpSpec followUp,
            List<GraphValidationError> errors) {

        if (followUp == null || !followUp.enabled()) {
            return;
        }

        String graphPath = "root.reportDefinition.followUp";

        validateFollowUpBase(
                followUp,
                graphPath,
                errors
        );

        validateFollowUpInputMapping(
                followUp,
                graphPath,
                errors
        );
    }

    /**
     * 校验追问提示、目标工作流和候选数据路径。
     */
    private void validateFollowUpBase(
            ReportFollowUpSpec followUp,
            String graphPath,
            List<GraphValidationError> errors) {

        if (!StringUtils.hasText(followUp.prompt())
                || followUp.prompt().trim().length()
                > MAX_FOLLOW_UP_PROMPT_LENGTH) {

            errors.add(error(
                    "REPORT_FOLLOW_UP_PROMPT_INVALID",
                    graphPath,
                    "追问提示不能为空且不能超过"
                            + MAX_FOLLOW_UP_PROMPT_LENGTH
                            + "个字符"
            ));
        }

        validateFollowUpTargetType(followUp.targetType(),graphPath,errors);

        validateFollowUpTargetCode(followUp.targetCode(),graphPath,errors);

        if (!isAbsoluteArrayPath(followUp.optionRowPath())) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_ROW_PATH_INVALID",
                    graphPath,
                    "追问候选行路径必须是以 $. 开头并以 [] 结尾的安全路径"
            ));
        }

        if (!isRelativeScalarPath(followUp.optionKeyPath())) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_KEY_PATH_INVALID",
                    graphPath,
                    "追问候选键必须是相对标量路径"
            ));
        }

        if (!isRelativeScalarPath(followUp.optionLabelPath())) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_LABEL_PATH_INVALID",
                    graphPath,
                    "追问候选名称必须是相对标量路径"
            ));
        }
    }

    /**
     * 校验追问目标类型。
     */
    private void validateFollowUpTargetType(String targetType,String graphPath,List<GraphValidationError> errors) {

        if (isSupportedFollowUpTargetType(targetType)) {
            return;
        }
        errors.add(error(
                "REPORT_FOLLOW_UP_TARGET_TYPE_INVALID",
                graphPath,
                "追问目标类型只允许 CAPABILITY 或 WORKFLOW"
        ));
    }

    /**
     * 判断是否为支持的追问目标类型。
     */
    private boolean isSupportedFollowUpTargetType(String targetType) {
        if (!StringUtils.hasText(targetType)) {
            return false;
        }
        String value = targetType.trim();
        return FOLLOW_UP_TARGET_TYPES.stream().anyMatch(item ->item.equalsIgnoreCase(value) );
    }

    /**
     * 校验目标工作流输入映射。
     */
    private void validateFollowUpInputMapping(
            ReportFollowUpSpec followUp,
            String graphPath,
            List<GraphValidationError> errors) {

        Map<String, Object> inputMapping =
                followUp.inputMapping();

        if (inputMapping.isEmpty()) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_INPUT_REQUIRED",
                    graphPath,
                    "追问必须配置目标工作流输入映射"
            ));
            return;
        }

        if (inputMapping.size() > MAX_FOLLOW_UP_INPUT_COUNT) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_INPUT_LIMIT",
                    graphPath,
                    "追问输入映射不能超过"
                            + MAX_FOLLOW_UP_INPUT_COUNT
                            + "项"
            ));
        }

        boolean selectionReferenced = false;

        for (Map.Entry<String, Object> entry :
                inputMapping.entrySet()) {

            String inputPath =
                    graphPath
                            + ".inputMapping."
                            + entry.getKey();

            validateFollowUpInputKey(
                    entry.getKey(),
                    inputPath,
                    errors
            );

            if (isSelectedExpression(entry.getValue())) {
                selectionReferenced = true;
            }

            validateFollowUpInputValue(
                    entry.getValue(),
                    inputPath,
                    errors
            );
        }

        /*
         * 至少一个目标参数必须来自用户选择的候选行，
         * 否则用户回答不会影响目标工作流执行。
         */
        if (!selectionReferenced) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_SELECTION_REQUIRED",
                    graphPath,
                    "追问输入映射至少需要引用一个 $selected 字段"
            ));
        }
    }

    /**
     * 校验目标能力编码或目标工作流编码。
     */
    private void validateFollowUpTargetCode(
            String targetCode,
            String graphPath,
            List<GraphValidationError> errors) {

        if (StringUtils.hasText(targetCode)&& FOLLOW_UP_TARGET_CODE.matcher( targetCode.trim()).matches()) {
            return;
        }
        errors.add(error(
                "REPORT_FOLLOW_UP_TARGET_CODE_INVALID",
                graphPath,
                "追问目标编码格式不正确"
        ));
    }

    /**
     * 校验目标工作流参数名称。
     */
    private void validateFollowUpInputKey(
            String key,
            String graphPath,
            List<GraphValidationError> errors) {

        if (!StringUtils.hasText(key)
                || !INPUT_KEY.matcher(key.trim()).matches()) {

            errors.add(error(
                    "REPORT_FOLLOW_UP_INPUT_KEY_INVALID",
                    graphPath,
                    "追问目标参数名称格式不正确"
            ));
        }
    }

    /**
     * 校验映射值只能是安全路径或标量常量。
     */
    private void validateFollowUpInputValue(
            Object value,
            String graphPath,
            List<GraphValidationError> errors) {

        if (value == null) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_INPUT_VALUE_INVALID",
                    graphPath,
                    "追问输入映射值不能为空"
            ));
            return;
        }

        if (value instanceof Number
                || value instanceof Boolean) {
            return;
        }

        if (!(value instanceof String text)) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_INPUT_VALUE_INVALID",
                    graphPath,
                    "追问输入映射只允许字符串、数字和布尔值"
            ));
            return;
        }

        String expression = text.trim();

        if (expression.startsWith(SOURCE_PREFIX)) {
            validateSourceExpression(
                    expression,
                    graphPath,
                    errors
            );
            return;
        }

        if (expression.startsWith(SELECTED_PREFIX)) {
            validateSelectedExpression(
                    expression,
                    graphPath,
                    errors
            );
            return;
        }

        if (expression.startsWith("$")) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_EXPRESSION_INVALID",
                    graphPath,
                    "追问映射表达式只支持 $source 和 $selected"
            ));
            return;
        }

        if (expression.length() > MAX_LITERAL_LENGTH) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_LITERAL_LIMIT",
                    graphPath,
                    "追问固定字符串不能超过"
                            + MAX_LITERAL_LENGTH
                            + "个字符"
            ));
        }
    }

    /**
     * 校验来源报告中的标量路径。
     */
    private void validateSourceExpression(
            String expression,
            String graphPath,
            List<GraphValidationError> errors) {

        String sourcePath =
                "$."
                        + expression.substring(
                        SOURCE_PREFIX.length()
                );

        if (!isAbsoluteScalarPath(sourcePath)) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_SOURCE_PATH_INVALID",
                    graphPath,
                    "$source 必须引用来源报告中的安全标量路径"
            ));
        }
    }

    /**
     * 校验用户选中候选行中的标量路径。
     */
    private void validateSelectedExpression(
            String expression,
            String graphPath,
            List<GraphValidationError> errors) {

        String selectedPath = expression.substring(SELECTED_PREFIX.length());
        if (!isRelativeScalarPath(selectedPath)) {
            errors.add(error(
                    "REPORT_FOLLOW_UP_SELECTED_PATH_INVALID",
                    graphPath,
                    "$selected 必须引用候选行中的安全标量路径"
            ));
        }
    }

    /**
     * 判断映射是否引用用户选择结果。
     */
    private boolean isSelectedExpression(Object value) {
        return value instanceof String text && text.trim().startsWith(SELECTED_PREFIX);
    }

    private void validateSection(
            ReportSectionSpec section,
            int index,
            List<GraphValidationError> errors) {

        String graphPath =
                "root.reportDefinition.sections[" + index + "]";

        if (section == null || section.type() == null) {
            errors.add(error(
                    "REPORT_SECTION_TYPE_REQUIRED",
                    graphPath,
                    "报告区块类型不能为空"
            ));
            return;
        }

        if (!StringUtils.hasText(section.title())) {
            errors.add(error(
                    "REPORT_SECTION_TITLE_REQUIRED",
                    graphPath,
                    "报告区块标题不能为空"
            ));
        }

        if (section.fields().isEmpty()) {
            errors.add(error(
                    "REPORT_FIELD_REQUIRED",
                    graphPath,
                    "报告区块至少需要一个字段"
            ));
            return;
        }

        if (section.fields().size() > MAX_FIELD_COUNT) {
            errors.add(error(
                    "REPORT_FIELD_LIMIT",
                    graphPath,
                    "单个报告区块字段数量不能超过"
                            + MAX_FIELD_COUNT
            ));
        }

        if (isItemSection(section.type())) {
            validateItemSection(section, graphPath, errors);
        } else {
            validateTableSection(section, graphPath, errors);
        }

        validateFields(section, graphPath, errors);
    }

    private void validateItemSection(
            ReportSectionSpec section,
            String graphPath,
            List<GraphValidationError> errors) {

        if (StringUtils.hasText(section.rowPath())) {
            errors.add(error(
                    "REPORT_ROW_PATH_NOT_ALLOWED",
                    graphPath,
                    "KEY_VALUE 和 METRICS 不能配置 rowPath"
            ));
        }
    }

    /**
     * 校验表格区块配置。
     *
     * TREE_TABLE 必须选择嵌套树或平铺转树中的一种，
     * 不能同时配置，也不能全部为空。
     */
    private void validateTableSection(ReportSectionSpec section,
            String graphPath,List<GraphValidationError> errors) {
        if (!isAbsoluteArrayPath(section.rowPath())) {
            errors.add(error(
                    "REPORT_ROW_PATH_INVALID",
                    graphPath,
                    "TABLE 和 TREE_TABLE 的 rowPath 必须是以 $. 开头并以 [] 结尾的安全路径"
            ));
        }

        if (section.type() != ReportSectionType.TREE_TABLE) {
            return;
        }

        if (!isRelativeScalarPath(section.rowKeyPath())) {
            errors.add(error(
                    "REPORT_ROW_KEY_INVALID",
                    graphPath,
                    "TREE_TABLE 必须配置相对行路径 rowKeyPath"
            ));
        }

        boolean nestedTree = StringUtils.hasText(section.childrenPath());

        boolean flatTree = StringUtils.hasText(section.parentKeyPath());

        if (nestedTree == flatTree) {
            errors.add(error(
                    "REPORT_TREE_SOURCE_INVALID",
                    graphPath,
                    "TREE_TABLE 必须且只能配置 childrenPath 或 parentKeyPath"
            ));
        }

        if (nestedTree && !isRelativeArrayPath(section.childrenPath())) {

            errors.add(error(
                    "REPORT_CHILDREN_PATH_INVALID",
                    graphPath,
                    "childrenPath 必须是以 [] 结尾的相对子节点路径"
            ));
        }

        if (flatTree && !isRelativeScalarPath(section.parentKeyPath())) {

            errors.add(error(
                    "REPORT_PARENT_KEY_INVALID",
                    graphPath,
                    "parentKeyPath 必须是相对标量路径"
            ));
        }

        if (!flatTree && StringUtils.hasText(section.rootParentValue())) {

            errors.add(error(
                    "REPORT_ROOT_PARENT_NOT_ALLOWED",
                    graphPath,
                    "只有平铺转树模式可以配置 rootParentValue"
            ));
        }

        if (StringUtils.hasText(section.summaryPath()) && !isRelativeScalarPath(section.summaryPath())) {

            errors.add(error(
                    "REPORT_SUMMARY_PATH_INVALID",
                    graphPath,
                    "summaryPath 必须是相对标量路径"
            ));
        }
    }

    /**
     * 校验普通字段和文件列表字段。
     */
    private void validateFields(
            ReportSectionSpec section,
            String graphPath,
            List<GraphValidationError> errors) {

        Set<String> keys = new HashSet<>();
        Set<Long> fieldIds = new HashSet<>();

        for (int index = 0; index < section.fields().size(); index++) {

            ReportFieldBindingSpec field = section.fields().get(index);
            String fieldPath = graphPath + ".fields[" + index + "]";

            if (field == null) {
                errors.add(error(
                        "REPORT_FIELD_INVALID",
                        fieldPath,
                        "报告字段配置不能为空"
                ));
                continue;
            }

            if (!StringUtils.hasText(field.key())
                    || !keys.add(field.key().trim())) {

                errors.add(error(
                        "REPORT_FIELD_KEY_INVALID",
                        fieldPath,
                        "报告字段 key 不能为空且不能重复"
                ));
            }

            if (section.type() == ReportSectionType.TREE_TABLE
                    && RESERVED_TREE_KEYS.contains(field.key())) {

                errors.add(error(
                        "REPORT_FIELD_KEY_RESERVED",
                        fieldPath,
                        "TREE_TABLE 字段不能使用系统保留名称"
                ));
            }

            if (field.fieldId() == null
                    || field.fieldId() <= 0
                    || !fieldIds.add(field.fieldId())) {

                errors.add(error(
                        "REPORT_FIELD_ID_INVALID",
                        fieldPath,
                        "字段字典 ID 必须有效且不能重复"
                ));
            }

            boolean fileBinding = field.fileList();

            if (field.hasAnyFileConfig() && !fileBinding) {
                errors.add(error(
                        "REPORT_FILE_BINDING_INCOMPLETE",
                        fieldPath,
                        "文件字段必须同时配置文件地址字段、文件名路径和文件地址路径"
                ));
            }

            if (fileBinding
                    && (field.fileUrlFieldId() <= 0
                    || !fieldIds.add(field.fileUrlFieldId()))) {

                errors.add(error(
                        "REPORT_FILE_URL_FIELD_INVALID",
                        fieldPath,
                        "文件地址字段字典 ID 必须有效且不能重复"
                ));
            }

            boolean validPath = isItemSection(section.type())
                    ? fileBinding
                      ? isAbsoluteArrayPath(field.sourcePath())
                      : isAbsoluteScalarPath(field.sourcePath())
                    : fileBinding
                      ? isRelativeArrayPath(field.sourcePath())
                      : isRelativeScalarPath(field.sourcePath());

            if (!validPath) {
                errors.add(error(
                        "REPORT_FIELD_PATH_INVALID",
                        fieldPath,
                        fileBinding
                                ? "文件数组路径格式不正确"
                                : "报告字段路径格式不正确"
                ));
            }

            if (!fileBinding) {
                continue;
            }

            if (!isRelativeScalarPath(field.fileNamePath())) {
                errors.add(error(
                        "REPORT_FILE_NAME_PATH_INVALID",
                        fieldPath,
                        "文件名路径必须是相对标量路径"
                ));
            }

            if (!isRelativeScalarPath(field.fileUrlPath())) {
                errors.add(error(
                        "REPORT_FILE_URL_PATH_INVALID",
                        fieldPath,
                        "文件地址路径必须是相对标量路径"
                ));
            }

            if (field.fileNamePath().equals(field.fileUrlPath())) {
                errors.add(error(
                        "REPORT_FILE_PATH_DUPLICATED",
                        fieldPath,
                        "文件名路径和文件地址路径不能相同"
                ));
            }
        }
    }

    private boolean isItemSection(ReportSectionType type) {
        return type == ReportSectionType.KEY_VALUE
                || type == ReportSectionType.METRICS;
    }

    /**
     * 校验报告绝对标量路径。
     *
     * 允许路径穿过中间数组，但末级必须是标量字段。
     * 如果路径实际命中多个值，ReportValueReader 会拒绝处理，
     * 不会静默选择第一条业务数据。
     */
    private boolean isAbsoluteScalarPath(String path) {
        return isSafePath(path) && path.startsWith("$.") && !path.endsWith("[]");
    }

    private boolean isAbsoluteArrayPath(String path) {
        return isSafePath(path)
                && path.startsWith("$.")
                && path.endsWith("[]");
    }

    private boolean isRelativeScalarPath(String path) {
        return isSafePath(path)
                && !path.startsWith("$.")
                && !path.contains("[]");
    }

    private boolean isRelativeArrayPath(String path) {
        return isSafePath(path)
                && !path.startsWith("$.")
                && path.endsWith("[]");
    }

    private boolean isSafePath(String path) {
        return StringUtils.hasText(path)
                && SAFE_PATH.matcher(path.trim()).matches();
    }

    private GraphValidationError error(
            String code,
            String graphPath,
            String message) {

        return new GraphValidationError(
                code,
                graphPath,
                null,
                null,
                message
        );
    }
}