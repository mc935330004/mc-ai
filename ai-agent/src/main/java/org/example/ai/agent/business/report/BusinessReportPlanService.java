package org.example.ai.agent.business.report;

import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 确定性构造组合报告逻辑计划。
 *
 * 模板选择和章节解析由可注入协作者完成；本服务不复制数据权限，也不执行报告渲染。
 */
public class BusinessReportPlanService {

    public static final String DENIED_MESSAGE = "因权限不足未纳入";
    public static final String FAILED_MESSAGE = "数据查询失败，章节未纳入";
    private static final Set<String> FORMATS = Set.of("XLSX", "DOCX", "PDF");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private static final int MAX_SECTIONS = 64;

    private final TemplateResolver templateResolver;
    private final SectionResolver sectionResolver;

    public BusinessReportPlanService(
            TemplateResolver templateResolver,
            SectionResolver sectionResolver) {
        this.templateResolver = Objects.requireNonNull(templateResolver, "templateResolver不能为空");
        this.sectionResolver = Objects.requireNonNull(sectionResolver, "sectionResolver不能为空");
    }

    /**
     * 先解析主体适配模板，再应用用户显式章节覆盖，最后逐章节确定复用或查询决策。
     */
    public PlannedReport plan(PlanCommand command) {
        ValidatedCommand validated = validate(command);
        TemplateDefinition template = Objects.requireNonNull(
                templateResolver.resolve(validated.subjectType(), validated.projectType()),
                "未找到可用组合报告模板"
        );
        validateTemplate(template);
        List<TemplateSection> selected = applyOverrides(
                template.sections(), validated.includeSections(), validated.excludeSections()
        );
        List<LogicalReportSection> resolved = new ArrayList<>(selected.size());
        boolean dataComplete = true;
        for (TemplateSection section : selected) {
            ResolvedSection result = sectionResolver.resolve(new SectionCommand(
                    validated.agentRunId(), validated.userId(), validated.sessionId(),
                    validated.authorization(), validated.secureContext(), validated.subjectType(),
                    validated.subjectId(), section.datasetCode(), validated.canonicalQuery(),
                    validated.refreshRequested()
            ));
            LogicalReportSection logical = sanitize(section, result);
            resolved.add(logical);
            dataComplete &= result.dataComplete()
                    && Set.of(
                    SectionResolutionStatus.REUSED,
                    SectionResolutionStatus.QUERIED,
                    SectionResolutionStatus.EMPTY
            ).contains(result.status());
        }
        LogicalReportPlan plan = new LogicalReportPlan(
                template.templateCode(), validated.subjectType(), validated.subjectId(),
                validated.format(), List.copyOf(resolved), dataComplete
        );
        return new PlannedReport(plan, template.configChecksum());
    }

    private ValidatedCommand validate(PlanCommand command) {
        if (command == null || command.subjectType() == null) {
            throw new IllegalArgumentException("报告计划命令不完整");
        }
        requireText(command.agentRunId(), 128, "agentRunId");
        requireText(command.userId(), 128, "userId");
        requireText(command.sessionId(), 64, "sessionId");
        requireText(command.authorization(), 4096, "authorization");
        requireText(command.subjectId(), 128, "subjectId");
        if (!FORMATS.contains(command.format())) {
            throw new IllegalArgumentException("format仅支持XLSX、DOCX或PDF，且必须保持规范大写");
        }
        if (command.subjectType() != BusinessSubjectType.PROJECT
                && StringUtils.hasText(command.projectType())) {
            throw new IllegalArgumentException("只有项目主体允许指定projectType");
        }
        if (StringUtils.hasText(command.projectType())) {
            requireText(command.projectType(), 64, "projectType");
        }
        List<String> includes = validateCodes(command.includeSections(), "includeSections");
        List<String> excludes = validateCodes(command.excludeSections(), "excludeSections");
        Object safeContext = ReportDatasetValidator.freezeSafeValue(command.secureContext());
        Object safeQuery = ReportDatasetValidator.freezeSafeValue(command.canonicalQuery());
        if (!(safeContext instanceof Map<?, ?>) || !(safeQuery instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("安全上下文和规范查询必须是Map");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> context = (Map<String, Object>) safeContext;
        @SuppressWarnings("unchecked")
        Map<String, Object> query = (Map<String, Object>) safeQuery;
        return new ValidatedCommand(
                command.agentRunId(), command.userId(), command.sessionId(),
                command.authorization(), context, command.subjectType(), command.subjectId(),
                command.projectType(), command.format(), query, includes, excludes,
                command.refreshRequested()
        );
    }

    private void validateTemplate(TemplateDefinition template) {
        requireCode(template.templateCode(), "templateCode");
        requireChecksum(template.configChecksum(), "templateChecksum");
        if (template.sections() == null || template.sections().isEmpty()
                || template.sections().size() > MAX_SECTIONS) {
            throw new IllegalArgumentException("模板章节数量不合法");
        }
        Set<String> codes = new LinkedHashSet<>();
        int lastOrder = Integer.MIN_VALUE;
        for (TemplateSection section : template.sections()) {
            if (section == null) {
                throw new IllegalArgumentException("模板章节不能为空");
            }
            requireCode(section.datasetCode(), "datasetCode");
            if (!codes.add(section.datasetCode()) || section.displayOrder() < 0
                    || section.displayOrder() < lastOrder) {
                throw new IllegalArgumentException("模板章节编码重复或顺序不合法");
            }
            lastOrder = section.displayOrder();
        }
    }

    private List<TemplateSection> applyOverrides(
            List<TemplateSection> sections,
            List<String> includes,
            List<String> excludes) {
        Set<String> excluded = Set.copyOf(excludes);
        Map<String, TemplateSection> selected = new LinkedHashMap<>();
        sections.stream()
                .filter(section -> !excluded.contains(section.datasetCode()))
                .forEach(section -> selected.put(section.datasetCode(), section));
        int nextOrder = sections.stream()
                .mapToInt(TemplateSection::displayOrder)
                .max()
                .orElse(0) + 1;
        for (String code : includes) {
            if (!excluded.contains(code) && !selected.containsKey(code)) {
                selected.put(code, new TemplateSection(code, nextOrder++));
            }
        }
        if (selected.isEmpty() || selected.size() > MAX_SECTIONS) {
            throw new IllegalArgumentException("显式章节覆盖后报告不能为空且最多包含64章");
        }
        return List.copyOf(selected.values());
    }

    private LogicalReportSection sanitize(
            TemplateSection expected,
            ResolvedSection result) {
        if (result == null || result.status() == null
                || !Objects.equals(expected.datasetCode(), result.datasetCode())) {
            throw new IllegalStateException("章节解析结果不完整或与请求不一致");
        }
        requireChecksum(result.fieldPolicyChecksum(), "fieldPolicyChecksum");
        if (Set.of(
                SectionResolutionStatus.REUSED,
                SectionResolutionStatus.QUERIED,
                SectionResolutionStatus.EMPTY
        )
                .contains(result.status()) && !StringUtils.hasText(result.snapshotId())) {
            throw new IllegalStateException("已解析章节必须引用安全业务快照");
        }
        if (!Set.of(
                SectionResolutionStatus.REUSED,
                SectionResolutionStatus.QUERIED,
                SectionResolutionStatus.EMPTY
        )
                .contains(result.status()) && StringUtils.hasText(result.snapshotId())) {
            throw new IllegalStateException("未完成章节不能引用快照");
        }
        String safeMessage = switch (result.status()) {
            case DENIED -> DENIED_MESSAGE;
            case FAILED -> FAILED_MESSAGE;
            default -> null;
        };
        return new LogicalReportSection(
                expected.datasetCode(), result.snapshotId(), result.fieldPolicyChecksum(),
                result.status().name(), safeMessage
        );
    }

    private List<String> validateCodes(List<String> source, String field) {
        if (source == null) {
            return List.of();
        }
        if (source.size() > MAX_SECTIONS) {
            throw new IllegalArgumentException(field + "最多包含64项");
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String code : source) {
            requireCode(code, field);
            if (!unique.add(code)) {
                throw new IllegalArgumentException(field + "不能包含重复项");
            }
        }
        return List.copyOf(unique);
    }

    private void requireCode(String value, String field) {
        if (value == null || !CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(field + "不合法");
        }
    }

    private void requireChecksum(String value, String field) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + "必须是64位SHA-256十六进制");
        }
    }

    private void requireText(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value) || value.length() > maxLength
                || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + "不合法");
        }
    }

    /** 按主体类型及项目细分选择当前模板。 */
    @FunctionalInterface
    public interface TemplateResolver {
        TemplateDefinition resolve(BusinessSubjectType subjectType, String projectType);
    }

    /**
     * 确定性解析章节。实现应复用 BusinessSnapshotMatcher 及现有访问服务，禁止本地复制权限规则。
     */
    @FunctionalInterface
    public interface SectionResolver {
        ResolvedSection resolve(SectionCommand command);
    }

    public enum SectionResolutionStatus {
        REUSED,
        QUERIED,
        EMPTY,
        DENIED,
        FAILED
    }

    public record PlanCommand(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            BusinessSubjectType subjectType,
            String subjectId,
            String projectType,
            String format,
            Map<String, Object> canonicalQuery,
            List<String> includeSections,
            List<String> excludeSections,
            boolean refreshRequested) {

        public PlanCommand {
            secureContext = secureContext == null ? Map.of() : secureContext;
            canonicalQuery = canonicalQuery == null ? Map.of() : canonicalQuery;
            includeSections = includeSections == null ? List.of() : includeSections;
            excludeSections = excludeSections == null ? List.of() : excludeSections;
        }

        /** 日志禁止输出认证、上下文和查询值。 */
        @Override
        public String toString() {
            return "PlanCommand[userId=" + userId
                    + ", sessionId=" + sessionId
                    + ", subjectType=" + subjectType
                    + ", format=" + format
                    + ", authorizationPresent=" + StringUtils.hasText(authorization)
                    + ", includeCount=" + includeSections.size()
                    + ", excludeCount=" + excludeSections.size() + ']';
        }
    }

    public record TemplateDefinition(
            String templateCode,
            String configChecksum,
            List<TemplateSection> sections) {
    }

    public record TemplateSection(String datasetCode, int displayOrder) {
    }

    public record SectionCommand(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            BusinessSubjectType subjectType,
            String subjectId,
            String datasetCode,
            Map<String, Object> canonicalQuery,
            boolean refreshRequested) {

        /** 日志只保留路由摘要。 */
        @Override
        public String toString() {
            return "SectionCommand[userId=" + userId
                    + ", sessionId=" + sessionId
                    + ", subjectType=" + subjectType
                    + ", datasetCode=" + datasetCode
                    + ", authorizationPresent=" + StringUtils.hasText(authorization) + ']';
        }
    }

    public record ResolvedSection(
            String datasetCode,
            String snapshotId,
            String fieldPolicyChecksum,
            SectionResolutionStatus status,
            boolean dataComplete,
            String safeMessage) {
    }

    public record LogicalReportSection(
            String datasetCode,
            String snapshotId,
            String fieldPolicyChecksum,
            String status,
            String safeMessage) {
    }

    /** Task13约定的稳定逻辑报告计划。 */
    public record LogicalReportPlan(
            String templateCode,
            BusinessSubjectType subjectType,
            String subjectId,
            String format,
            List<LogicalReportSection> sections,
            boolean dataComplete) {
    }

    /** 将严格六字段逻辑计划与本次实际模板版本绑定。 */
    public record PlannedReport(
            LogicalReportPlan plan,
            String templateChecksum) {
    }

    private record ValidatedCommand(
            String agentRunId,
            String userId,
            String sessionId,
            String authorization,
            Map<String, Object> secureContext,
            BusinessSubjectType subjectType,
            String subjectId,
            String projectType,
            String format,
            Map<String, Object> canonicalQuery,
            List<String> includeSections,
            List<String> excludeSections,
            boolean refreshRequested) {
    }
}
