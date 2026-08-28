package org.example.ai.agent.workflow.answer.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.answer.formatter.FactValueFormatter;
import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.common.enums.FactSourceType;
import org.example.ai.agent.common.enums.report.ReportAggregationType;
import org.example.ai.agent.common.enums.report.ReportCalculationOperator;
import org.example.ai.agent.graph.model.report.ReportCalculationSpec;
import org.example.ai.agent.graph.model.report.ReportCalculationTermSpec;
import org.example.ai.agent.graph.model.report.ReportSectionSpec;
import org.example.ai.agent.tool.FieldMeta;
import org.example.ai.agent.workflow.answer.WorkflowAnswerPreparation;
import org.example.ai.agent.workflow.answer.report.config.ReportDefinitionResolver;
import org.example.ai.agent.workflow.answer.report.config.ReportValueReader;
import org.example.ai.agent.workflow.answer.report.config.ResolvedReportDefinition;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 将工作流中的安全计算公式转换为统一计算事实。
 *
 * 只允许字段聚合和四则运算，
 * 不执行脚本、SQL、SpEL和大模型计算。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowCalculationFactService {

    private static final int DIVIDE_SCALE = 8;

    private final ObjectMapper objectMapper;
    private final ReportDefinitionResolver reportDefinitionResolver;
    private final ReportValueReader valueReader;
    private final FactValueFormatter valueFormatter;

    /**
     * 计算当前工作流发布版本中的全部配置指标。
     */
    public List<AnswerFact> calculate(
            WorkflowAnswerPreparation preparation) {

        if (preparation == null
                || preparation.outcome() == null
                || preparation.internalPayload() == null) {

            return List.of();
        }

        String workflowCode =
                preparation.outcome().workflowCode();

        try {
            Optional<ResolvedReportDefinition> optional =
                    reportDefinitionResolver.resolve(
                            preparation.outcome()
                    );

            if (optional.isEmpty()) {
                return List.of();
            }

            ResolvedReportDefinition resolved =
                    optional.get();

            JsonNode resultRoot =
                    objectMapper.valueToTree(
                            preparation.internalPayload()
                                    .result()
                    );

            List<AnswerFact> facts = new ArrayList<>();
            int displayOrder = 1_000;

            for (ReportSectionSpec section :
                    resolved.definition().sections()) {

                for (ReportCalculationSpec calculation :
                        section.calculations()) {

                    facts.add(
                            calculateFact(
                                    calculation,
                                    resolved,
                                    resultRoot,
                                    workflowCode,
                                    section.title(),
                                    displayOrder
                            )
                    );

                    displayOrder++;
                }
            }

            return List.copyOf(facts);

        } catch (RuntimeException exception) {

            log.warn(
                    "工作流计算配置解析失败，workflowCode={}，errorType={}",
                    workflowCode,
                    exception.getClass().getSimpleName()
            );

            return List.of(
                    buildFailureFact(
                            workflowCode,
                            "calculation_config",
                            "计算指标",
                            "计算指标",
                            1_000,
                            CalculationProvenance.empty(),
                            "CALCULATION_CONFIG_INVALID",
                            "已配置指标暂时无法计算。"
                    )
            );
        }
    }

    /**
     * 计算一个配置指标并生成统一事实。
     *
     * REPORT和CHAT共用本方法。
     */
    public AnswerFact calculateFact(
            ReportCalculationSpec calculation,
            ResolvedReportDefinition resolved,
            JsonNode safeResult,
            String scopeCode,
            String sectionTitle,
            int displayOrder) {

        String key = calculation == null
                        ? "calculation"
                        : safeText(
                                calculation.key(),
                                "calculation"
                        );

        String label =
                calculation == null
                        ? "计算指标"
                        : safeText(
                                calculation.label(),
                                key
                        );

        CalculationProvenance provenance = CalculationProvenance.empty();
        try {
            if (calculation == null) {
                throw new CalculationException(
                        "CALCULATION_CONFIG_INVALID",
                        "计算指标配置无效。"
                );
            }
            provenance = buildProvenance(calculation, resolved);
            BigDecimal value = calculateValue(calculation, safeResult);
            return buildSuccessFact(calculation, value, scopeCode, sectionTitle, displayOrder, provenance);
        } catch (CalculationException exception) {
            return buildFailureFact(scopeCode, key, label, sectionTitle, displayOrder, provenance, exception.code(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn(
                    "工作流计算指标执行失败，calculationKey={}，errorType={}",
                    key,
                    exception.getClass().getSimpleName()
            );

            return buildFailureFact(
                    scopeCode,
                    key,
                    label,
                    sectionTitle,
                    displayOrder,
                    provenance,
                    "CALCULATION_FAILED","指标“" + label + "”暂时无法计算。"
            );
        }
    }

    /**
     * 执行结构化计算公式。
     *
     * 运算顺序：
     * 先乘除，后加减。
     */
    private BigDecimal calculateValue(
            ReportCalculationSpec calculation,
            JsonNode safeResult) {

        List<ReportCalculationTermSpec> terms =
                calculation.terms();

        if (terms.isEmpty()) {
            throw new CalculationException(
                    "CALCULATION_TERM_EMPTY",
                    "指标“" + calculation.label()
                            + "”没有配置计算项。"
            );
        }

        BigDecimal current =
                aggregate(
                        terms.get(0),
                        safeResult
                );

        List<BigDecimal> additiveValues =
                new ArrayList<>();

        List<ReportCalculationOperator> additiveOperators =
                new ArrayList<>();

        for (int index = 1;
             index < terms.size();
             index++) {

            ReportCalculationTermSpec term =
                    terms.get(index);

            if (term.operator() == null) {
                throw new CalculationException(
                        "CALCULATION_OPERATOR_MISSING",
                        "指标“" + calculation.label()
                                + "”缺少运算符。"
                );
            }

            BigDecimal value =
                    aggregate(
                            term,
                            safeResult
                    );

            switch (term.operator()) {
                case MULTIPLY ->
                        current = current.multiply(value);

                case DIVIDE ->
                        current = divide(current, value);

                case ADD, SUBTRACT -> {
                    additiveValues.add(current);
                    additiveOperators.add(
                            term.operator()
                    );
                    current = value;
                }
            }
        }

        additiveValues.add(current);

        BigDecimal result =
                additiveValues.get(0);

        for (int index = 0;
             index < additiveOperators.size();
             index++) {

            BigDecimal value =
                    additiveValues.get(index + 1);

            result =
                    additiveOperators.get(index)
                            == ReportCalculationOperator.ADD
                            ? result.add(value)
                            : result.subtract(value);
        }

        return result.stripTrailingZeros();
    }

    /**
     * 聚合单个字段的业务值。
     */
    private BigDecimal aggregate(
            ReportCalculationTermSpec term,
            JsonNode safeResult) {

        if (term == null
                || term.aggregation() == null
                || !StringUtils.hasText(
                        term.sourcePath())) {

            throw new CalculationException(
                    "CALCULATION_TERM_INVALID",
                    "计算项配置不完整。"
            );
        }

        List<JsonNode> nodes =
                valueReader.readMany(
                        safeResult,
                        term.sourcePath()
                );

        if (term.aggregation()
                == ReportAggregationType.COUNT) {

            return BigDecimal.valueOf(
                    countScalarValues(nodes)
            );
        }

        List<BigDecimal> values =
                readNumbers(nodes);

        if (values.isEmpty()) {
            throw new CalculationException(
                    "CALCULATION_SOURCE_EMPTY",
                    "计算字段没有有效数值。"
            );
        }

        return switch (term.aggregation()) {
            case SUM -> sum(values);

            case AVG -> sum(values).divide(
                    BigDecimal.valueOf(values.size()),
                    DIVIDE_SCALE,
                    RoundingMode.HALF_UP
            );

            case MAX -> values.stream()
                    .max(BigDecimal::compareTo)
                    .orElseThrow();

            case MIN -> values.stream()
                    .min(BigDecimal::compareTo)
                    .orElseThrow();

            case COUNT -> throw new IllegalStateException(
                    "COUNT已经单独处理"
            );
        };
    }

    /**
     * COUNT只统计有效标量字段。
     */
    private int countScalarValues(
            List<JsonNode> nodes) {

        int count = 0;

        for (JsonNode node : nodes) {

            if (node == null
                    || node.isNull()) {

                continue;
            }

            if (node.isContainerNode()) {
                throw new CalculationException(
                        "CALCULATION_TYPE_MISMATCH",
                        "COUNT只能统计普通字段。"
                );
            }

            if (node.isTextual()
                    && !StringUtils.hasText(
                            node.asText())) {

                continue;
            }

            count++;
        }

        return count;
    }

    /**
     * 读取数字，同时兼容数字字符串。
     */
    private List<BigDecimal> readNumbers(
            List<JsonNode> nodes) {

        List<BigDecimal> values =
                new ArrayList<>();

        for (JsonNode node : nodes) {

            if (node == null
                    || node.isNull()) {

                continue;
            }

            if (node.isContainerNode()) {
                throw new CalculationException(
                        "CALCULATION_TYPE_MISMATCH",
                        "计算字段不能是对象或数组。"
                );
            }

            String text =
                    node.asText("")
                            .trim()
                            .replace(",", "")
                            .replace(" ", "");

            if (!StringUtils.hasText(text)) {
                continue;
            }

            try {
                values.add(
                        new BigDecimal(text)
                );

            } catch (NumberFormatException exception) {

                throw new CalculationException(
                        "CALCULATION_TYPE_MISMATCH",
                        "计算字段包含非数字值。"
                );
            }
        }

        return List.copyOf(values);
    }

    private BigDecimal sum(
            List<BigDecimal> values) {

        BigDecimal result =
                BigDecimal.ZERO;

        for (BigDecimal value : values) {
            result = result.add(value);
        }

        return result;
    }

    private BigDecimal divide(
            BigDecimal dividend,
            BigDecimal divisor) {

        if (divisor.compareTo(
                BigDecimal.ZERO) == 0) {

            throw new CalculationException(
                    "CALCULATION_DIVIDE_BY_ZERO",
                    "计算公式的除数不能为0。"
            );
        }

        return dividend.divide(
                divisor,
                DIVIDE_SCALE,
                RoundingMode.HALF_UP
        );
    }

    /**
     * 生成计算成功事实。
     */
    private AnswerFact buildSuccessFact(
            ReportCalculationSpec calculation,
            BigDecimal value,
            String scopeCode,
            String sectionTitle,
            int displayOrder,
            CalculationProvenance provenance) {

        FieldMeta displayMeta =
                FieldMeta.builder()
                        .name(calculation.key())
                        .fieldCode(calculation.key())
                        .cnName(calculation.label())
                        .type("number")
                        .format(calculation.displayFormat())
                        .precisionScale(
                                provenance.precisionScale()
                        )
                        .unit(provenance.unit())
                        .nullDisplayText("")
                        .build();

        String formattedValue =
                valueFormatter.format(
                        objectMapper.valueToTree(value),
                        displayMeta
                );

        return AnswerFact.builder()
                .factKey(
                        calculationFactKey(
                                scopeCode,
                                calculation.key()
                        )
                )
                .capabilityCode(
                        calculationScope(scopeCode)
                )
                .fieldCode(calculation.key())
                .fieldName(calculation.key())
                .fieldPath(
                        "$.calculations."
                                + calculation.key()
                )
                .label(calculation.label())
                .rawValue(value)
                .formattedValue(formattedValue)
                .valueType("number")
                .displayFormat(
                        calculation.displayFormat()
                )
                .meaning("后端配置公式计算结果")
                .displayGroup(sectionTitle)
                .importance("HIGH")
                .displayComponent("METRICS")
                .summary(true)
                .unit(provenance.unit())
                .precisionScale(
                        provenance.precisionScale()
                )
                .displayOrder(displayOrder)
                .requiredOutput(true)
                .modelVisible(
                        provenance.modelVisible()
                )
                .userVisible(true)
                .missing(false)
                .sourceType(FactSourceType.CALCULATED)
                .calculationExpression(
                        provenance.expression()
                )
                .sourceFieldCodes(
                        provenance.sourceFieldCodes()
                )
                .calculationStatus("SUCCESS")
                .build();
    }

    /**
     * 生成计算失败事实。
     *
     * 失败事实会形成确定性提示，
     * 不允许模型自行补算。
     */
    private AnswerFact buildFailureFact(
            String scopeCode,
            String calculationKey,
            String label,
            String sectionTitle,
            int displayOrder,
            CalculationProvenance provenance,
            String errorCode,
            String userMessage) {

        return AnswerFact.builder()
                .factKey(
                        calculationFactKey(
                                scopeCode,
                                calculationKey
                        )
                )
                .capabilityCode(
                        calculationScope(scopeCode)
                )
                .fieldCode(calculationKey)
                .fieldName(calculationKey)
                .fieldPath(
                        "$.calculations."
                                + calculationKey
                )
                .label(label)
                .rawValue(null)
                .formattedValue(userMessage)
                .valueType("text")
                .displayFormat("text")
                .meaning("后端配置公式计算失败")
                .displayGroup(sectionTitle)
                .importance("HIGH")
                .displayComponent("CALLOUT")
                .summary(false)
                .displayOrder(displayOrder)
                .requiredOutput(true)
                .modelVisible(false)
                .userVisible(true)
                .missing(true)
                .missingReason(errorCode)
                .sourceType(FactSourceType.CALCULATED)
                .calculationExpression(
                        provenance.expression()
                )
                .sourceFieldCodes(
                        provenance.sourceFieldCodes()
                )
                .calculationStatus("FAILED")
                .build();
    }

    /**
     * 创建计算公式审计信息。
     */
    private CalculationProvenance buildProvenance(
            ReportCalculationSpec calculation,
            ResolvedReportDefinition resolved) {

        List<String> sourceFieldCodes =
                new ArrayList<>();

        List<FieldDictionary> sourceFields =
                new ArrayList<>();

        StringBuilder expression =
                new StringBuilder();

        for (int index = 0;
             index < calculation.terms().size();
             index++) {

            ReportCalculationTermSpec term =
                    calculation.terms().get(index);

            FieldDictionary dictionary =
                    resolved.requireField(
                            term.fieldId()
                    );

            String fieldCode =
                    safeText(
                            dictionary.getFieldCode(),
                            dictionary.getFieldName()
                    );

            sourceFields.add(dictionary);
            sourceFieldCodes.add(fieldCode);

            if (index > 0) {
                expression.append(" ")
                        .append(
                                operatorText(
                                        term.operator()
                                )
                        )
                        .append(" ");
            }

            expression.append(
                            term.aggregation().name()
                    )
                    .append("(")
                    .append(fieldCode)
                    .append(")");
        }

        return new CalculationProvenance(
                expression.toString(),
                sourceFieldCodes.stream()
                        .filter(StringUtils::hasText)
                        .distinct()
                        .toList(),
                resolveUnit(
                        calculation,
                        sourceFields
                ),
                resolvePrecision(sourceFields),
                sourceFields.stream()
                        .allMatch(field ->
                                !Integer.valueOf(0)
                                        .equals(
                                                field.getModelVisible()
                                        )
                        )
        );
    }

    /**
     * 加减运算且来源单位一致时才保留单位。
     *
     * 乘除运算后的单位含义无法可靠推断，
     * 因此不自动编造单位。
     */
    private String resolveUnit(
            ReportCalculationSpec calculation,
            List<FieldDictionary> fields) {

        boolean unitCanBeInherited =
                calculation.terms().stream()
                        .skip(1)
                        .allMatch(term ->
                                term.operator()
                                        == ReportCalculationOperator.ADD
                                        || term.operator()
                                        == ReportCalculationOperator.SUBTRACT
                        );

        boolean containsCount =
                calculation.terms().stream()
                        .anyMatch(term ->
                                term.aggregation()
                                        == ReportAggregationType.COUNT
                        );

        if (!unitCanBeInherited
                || containsCount) {

            return null;
        }

        Set<String> units =
                new LinkedHashSet<>();

        for (FieldDictionary field : fields) {
            if (StringUtils.hasText(
                    field.getUnit())) {

                units.add(
                        field.getUnit().trim()
                );
            }
        }

        return units.size() == 1
                ? units.iterator().next()
                : null;
    }

    /**
     * 来源字段精度完全一致时才继承。
     */
    private Integer resolvePrecision(
            List<FieldDictionary> fields) {

        Set<Integer> scales =
                new LinkedHashSet<>();

        for (FieldDictionary field : fields) {
            if (field.getPrecisionScale() != null) {
                scales.add(
                        field.getPrecisionScale()
                );
            }
        }

        return scales.size() == 1
                ? scales.iterator().next()
                : null;
    }

    private String operatorText(
            ReportCalculationOperator operator) {

        if (operator == null) {
            return "";
        }

        return switch (operator) {
            case ADD -> "+";
            case SUBTRACT -> "-";
            case MULTIPLY -> "*";
            case DIVIDE -> "/";
        };
    }

    private String calculationFactKey(
            String scopeCode,
            String calculationKey) {

        return calculationScope(scopeCode)
                + ":"
                + safeText(
                        calculationKey,
                        "calculation"
                );
    }

    private String calculationScope(
            String scopeCode) {

        return "workflow-calculation:"
                + safeText(
                        scopeCode,
                        "workflow"
                );
    }

    private String safeText(
            String value,
            String defaultValue) {

        return StringUtils.hasText(value)
                ? value.trim()
                : defaultValue;
    }

    /**
     * 计算来源审计信息。
     */
    private record CalculationProvenance(
            String expression,
            List<String> sourceFieldCodes,
            String unit,
            Integer precisionScale,
            boolean modelVisible) {

        private CalculationProvenance {
            sourceFieldCodes =
                    sourceFieldCodes == null
                            ? List.of()
                            : List.copyOf(sourceFieldCodes);
        }

        private static CalculationProvenance empty() {
            return new CalculationProvenance(
                    null,
                    List.of(),
                    null,
                    null,
                    false
            );
        }
    }

    /**
     * 可安全展示的计算异常。
     */
    private static final class CalculationException
            extends RuntimeException {

        private final String code;

        private CalculationException(
                String code,
                String message) {

            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}