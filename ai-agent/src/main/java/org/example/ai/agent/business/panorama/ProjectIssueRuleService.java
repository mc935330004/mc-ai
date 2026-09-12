package org.example.ai.agent.business.panorama;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.dataset.DatasetExecutionProofVerifier;
import org.example.ai.agent.business.dataset.entity.ReportDataset;
import org.example.ai.agent.business.dataset.entity.ReportDatasetField;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetFieldMapper;
import org.example.ai.agent.business.dataset.mapper.ReportDatasetMapper;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.panorama.entity.ProjectIssueRule;
import org.example.ai.agent.business.panorama.mapper.ProjectIssueRuleMapper;
import org.example.ai.agent.business.panorama.model.IssueMatchStatus;
import org.example.ai.agent.business.panorama.model.ProjectIssueResult;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 使用标准事实编码执行项目单模块和跨模块确定性问题规则。
 */
@Service
public class ProjectIssueRuleService {

    private static final Pattern FACT_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,127}$");
    private static final Pattern RULE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,127}$");
    private static final Set<String> OPERATORS = Set.of("GT", "GTE", "LT", "LTE", "EQ", "NE");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final String UNKNOWN_MESSAGE = "证据不足，无法判定";
    private static final String NOT_MATCHED_MESSAGE = "未命中";
    private static final int MAX_RULES = 200;
    private static final int MAX_MODULES = 32;

    private final ProjectIssueRuleMapper ruleMapper;
    private final ReportDatasetMapper datasetMapper;
    private final ReportDatasetFieldMapper fieldMapper;
    private final DatasetExecutionProofVerifier proofVerifier;
    private final ObjectMapper objectMapper;

    @Autowired
    public ProjectIssueRuleService(
            ProjectIssueRuleMapper ruleMapper,
            ReportDatasetMapper datasetMapper,
            ReportDatasetFieldMapper fieldMapper,
            DatasetExecutionProofVerifier proofVerifier,
            ObjectMapper objectMapper) {
        this.ruleMapper = Objects.requireNonNull(ruleMapper, "ruleMapper不能为空");
        this.datasetMapper = Objects.requireNonNull(datasetMapper, "datasetMapper不能为空");
        this.fieldMapper = Objects.requireNonNull(fieldMapper, "fieldMapper不能为空");
        this.proofVerifier = Objects.requireNonNull(proofVerifier, "proofVerifier不能为空");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper不能为空");
    }

    /**
     * 只从经过执行证明校验且在当前字段策略中标记为可计算的事实生成问题结论。
     */
    List<ProjectIssueResult> evaluate(
            Long profileId,
            List<ProjectPanoramaResult.ModuleResult> modules) {
        return evaluate(loadRules(profileId), modules);
    }

    /**
     * 在模块调用开始前冻结本次使用的规则集合，避免长耗时执行期间规则发生漂移。
     */
    @Transactional(readOnly = true)
    public RuleSet loadRules(Long profileId) {
        if (profileId == null || profileId <= 0) {
            throw new IllegalArgumentException("profileId不合法");
        }
        List<ProjectIssueRule> rules = ruleMapper.selectList(
                Wrappers.<ProjectIssueRule>lambdaQuery()
                        .eq(ProjectIssueRule::getProfileId, profileId)
                        .eq(ProjectIssueRule::getEnabled, true)
                        .orderByAsc(ProjectIssueRule::getId)
        );
        if (rules == null || rules.size() > MAX_RULES) {
            throw new IllegalStateException("项目问题规则数量不合法");
        }
        return new RuleSet(profileId, rules);
    }

    @Transactional(readOnly = true)
    public List<ProjectIssueResult> evaluate(
            RuleSet ruleSet,
            List<ProjectPanoramaResult.ModuleResult> modules) {
        Objects.requireNonNull(ruleSet, "ruleSet不能为空");
        if (modules == null || modules.size() > MAX_MODULES) {
            throw new IllegalArgumentException("项目全景模块结果数量不合法");
        }
        Map<String, Object> facts = collectCalculableFacts(modules);
        return evaluateFacts(ruleSet.rules(), facts);
    }

    private List<ProjectIssueResult> evaluateFacts(
            List<ProjectIssueRule> rules,
            Map<String, Object> calculableFacts) {
        Map<String, Object> facts = calculableFacts == null ? Map.of() : Map.copyOf(calculableFacts);
        List<ProjectIssueResult> results = new ArrayList<>(rules.size());
        for (ProjectIssueRule rule : rules) {
            results.add(evaluateRule(rule, facts));
        }
        return List.copyOf(results);
    }

    private Map<String, Object> collectCalculableFacts(
            List<ProjectPanoramaResult.ModuleResult> modules) {
        Map<String, Object> facts = new LinkedHashMap<>();
        Set<String> conflicts = new HashSet<>();
        for (ProjectPanoramaResult.ModuleResult module : modules) {
            collectModuleFacts(module, facts, conflicts);
        }
        return Map.copyOf(facts);
    }

    private void collectModuleFacts(
            ProjectPanoramaResult.ModuleResult module,
            Map<String, Object> facts,
            Set<String> conflicts) {
        try {
            DatasetExecutionResult result = trustedResult(module);
            if (result == null) {
                return;
            }
            ReportDataset dataset = currentDataset(module.datasetCode());
            if (!matchesCurrentConfiguration(dataset, result)) {
                return;
            }
            Set<String> calculableCodes = calculableFactCodes(dataset.getId());
            calculationFacts(result).forEach((code, value) -> {
                if (calculableCodes.contains(code) && !conflicts.contains(code)) {
                    mergeFact(facts, conflicts, code, value);
                }
            });
        } catch (RuntimeException ignored) {
            /* 配置读取或证明验证失败时，该模块不提供正式问题判断所需的事实。 */
        }
    }

    private DatasetExecutionResult trustedResult(ProjectPanoramaResult.ModuleResult module) {
        if (module == null || module.status() == null || !StringUtils.hasText(module.snapshotId())
                || module.executionResult() == null
                || !Set.of("SUCCESS", "EMPTY").contains(module.status().name())) {
            return null;
        }
        DatasetExecutionResult result = module.executionResult();
        try {
            if (!Objects.equals(module.datasetCode(), result.datasetCode())
                    || !proofVerifier.verify(result)) {
                return null;
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return result;
    }

    private ReportDataset currentDataset(String datasetCode) {
        return datasetMapper.selectEnabledByCode(datasetCode);
    }

    private boolean matchesCurrentConfiguration(
            ReportDataset dataset,
            DatasetExecutionResult result) {
        return dataset != null
                && dataset.getId() != null
                && Boolean.TRUE.equals(dataset.getEnabled())
                && Objects.equals(dataset.getDatasetCode(), result.datasetCode())
                && Objects.equals(dataset.getConfigChecksum(), result.source().datasetConfigChecksum())
                && Objects.equals(
                        dataset.getFieldPolicyChecksum(),
                        result.source().fieldPolicyChecksum()
                );
    }

    private Set<String> calculableFactCodes(Long datasetId) {
        List<ReportDatasetField> fields = fieldMapper.selectList(
                Wrappers.<ReportDatasetField>lambdaQuery()
                        .eq(ReportDatasetField::getDatasetId, datasetId)
                        .eq(ReportDatasetField::getCalculable, true)
        );
        if (fields == null) {
            return Set.of();
        }
        Set<String> codes = new HashSet<>();
        for (ReportDatasetField field : fields) {
            if (field != null && Boolean.TRUE.equals(field.getCalculable())
                    && StringUtils.hasText(field.getFactCode())
                    && FACT_CODE.matcher(field.getFactCode()).matches()) {
                codes.add(field.getFactCode());
            }
        }
        return Set.copyOf(codes);
    }

    private Map<String, Object> calculationFacts(DatasetExecutionResult result) {
        Object calculation = result.safeFacts().get("calculation");
        if (!(calculation instanceof Map<?, ?> values)) {
            return Map.of();
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key instanceof String code) {
                facts.put(code, value);
            }
        });
        return facts;
    }

    private void mergeFact(
            Map<String, Object> facts,
            Set<String> conflicts,
            String code,
            Object value) {
        if (value == null) {
            return;
        }
        if (!facts.containsKey(code)) {
            facts.put(code, value);
            return;
        }
        if (!Objects.equals(facts.get(code), value)) {
            facts.remove(code);
            conflicts.add(code);
        }
    }

    private ProjectIssueResult evaluateRule(
            ProjectIssueRule rule,
            Map<String, Object> facts) {
        String ruleCode = safeRuleCode(rule);
        String severity = safeSeverity(rule);
        if (!validMetadata(rule)) {
            return result(
                    ruleCode,
                    IssueMatchStatus.UNKNOWN,
                    severity,
                    UNKNOWN_MESSAGE,
                    List.of()
            );
        }
        try {
            Condition condition = parseCondition(rule.getConditionJson());
            List<String> evidence = condition.evidenceFactCodes();
            BigDecimal left = number(facts.get(condition.leftFact()));
            BigDecimal right = condition.rightFact() == null
                    ? condition.rightValue()
                    : number(facts.get(condition.rightFact()));
            if (left == null || right == null) {
                return result(ruleCode, IssueMatchStatus.UNKNOWN, severity, UNKNOWN_MESSAGE, evidence);
            }
            boolean matched = compare(left, right, condition.operator());
            return result(
                    ruleCode,
                    matched ? IssueMatchStatus.MATCHED : IssueMatchStatus.NOT_MATCHED,
                    severity,
                    matched ? safeMessage(rule.getMessageTemplate()) : NOT_MATCHED_MESSAGE,
                    evidence
            );
        } catch (RuntimeException ignored) {
            /* 配置错误不能转化为正式问题结论，对外统一呈现证据不足。 */
            return result(ruleCode, IssueMatchStatus.UNKNOWN, severity, UNKNOWN_MESSAGE, List.of());
        }
    }

    private Condition parseCondition(String conditionJson) {
        JsonNode root;
        try {
            root = objectMapper.readTree(conditionJson);
        } catch (Exception exception) {
            throw new IllegalArgumentException("规则条件不是合法JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("规则条件必须是对象");
        }
        Set<String> keys = new LinkedHashSet<>();
        root.fieldNames().forEachRemaining(keys::add);
        boolean usesFact = keys.equals(Set.of("operator", "leftFact", "rightFact"));
        boolean usesValue = keys.equals(Set.of("operator", "leftFact", "rightValue"));
        if (!usesFact && !usesValue) {
            throw new IllegalArgumentException("规则条件字段不合法");
        }
        String operator = text(root.get("operator"));
        String leftFact = factCode(root.get("leftFact"));
        String rightFact = usesFact ? factCode(root.get("rightFact")) : null;
        BigDecimal rightValue = usesValue ? decimal(root.get("rightValue")) : null;
        if (!OPERATORS.contains(operator)) {
            throw new IllegalArgumentException("规则比较符不合法");
        }
        return new Condition(operator, leftFact, rightFact, rightValue);
    }

    private boolean compare(BigDecimal left, BigDecimal right, String operator) {
        int comparison = left.compareTo(right);
        return switch (operator) {
            case "GT" -> comparison > 0;
            case "GTE" -> comparison >= 0;
            case "LT" -> comparison < 0;
            case "LTE" -> comparison <= 0;
            case "EQ" -> comparison == 0;
            case "NE" -> comparison != 0;
            default -> false;
        };
    }

    private BigDecimal number(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            return new BigDecimal(number.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private BigDecimal decimal(JsonNode value) {
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("规则常量必须是数字");
        }
        return value.decimalValue();
    }

    private String factCode(JsonNode value) {
        String code = text(value);
        if (!FACT_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("规则必须引用稳定事实编码");
        }
        return code;
    }

    private String text(JsonNode value) {
        if (value == null || !value.isTextual() || !StringUtils.hasText(value.textValue())) {
            throw new IllegalArgumentException("规则文本字段不合法");
        }
        return value.textValue().trim();
    }

    private String safeRuleCode(ProjectIssueRule rule) {
        if (rule == null || !Boolean.TRUE.equals(rule.getEnabled())
                || !StringUtils.hasText(rule.getRuleCode())
                || !RULE_CODE.matcher(rule.getRuleCode()).matches()) {
            return "INVALID_RULE";
        }
        return rule.getRuleCode();
    }

    private String safeSeverity(ProjectIssueRule rule) {
        return rule != null && SEVERITIES.contains(rule.getSeverity())
                ? rule.getSeverity()
                : "MEDIUM";
    }

    private boolean validMetadata(ProjectIssueRule rule) {
        return rule != null
                && Boolean.TRUE.equals(rule.getEnabled())
                && StringUtils.hasText(rule.getRuleCode())
                && RULE_CODE.matcher(rule.getRuleCode()).matches()
                && SEVERITIES.contains(rule.getSeverity());
    }

    private String safeMessage(String message) {
        if (!StringUtils.hasText(message) || message.length() > 1000) {
            return "已命中项目问题规则";
        }
        return message.trim();
    }

    private ProjectIssueResult result(
            String ruleCode,
            IssueMatchStatus status,
            String severity,
            String message,
            List<String> evidence) {
        return new ProjectIssueResult(ruleCode, status, severity, message, evidence);
    }

    private record Condition(
            String operator,
            String leftFact,
            String rightFact,
            BigDecimal rightValue) {

        private List<String> evidenceFactCodes() {
            return rightFact == null
                    ? List.of(leftFact)
                    : List.of(leftFact, rightFact);
        }
    }

    public record RuleSet(
            Long profileId,
            List<ProjectIssueRule> rules) {

        public RuleSet {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }
}
