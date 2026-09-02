package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.dataset.DatasetExecutionProofVerifier;
import org.example.ai.agent.business.dataset.ReportDatasetExecutionService;
import org.example.ai.agent.business.dataset.ReportDatasetValidator;
import org.example.ai.agent.business.dataset.model.DatasetExecutionRequest;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;
import org.example.ai.agent.business.dataset.model.DatasetExecutionSource;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.model.DatasetExecutionStatus;
import org.example.ai.agent.business.subject.model.SubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.chat.support.ContentHashUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 通过已注册报告数据集执行项目、人员和部门授权目录查询。
 *
 * 配置项只保存数据集编码，不保存或猜测工作流编码；具体 READ 工作流、参数映射和权限工作流均由数据集当前配置决定。
 * 返回页的总数和翻页标志也必须相互一致，避免把任意一条结果误判为唯一主体。
 */
@Service
public class WorkflowBackedSubjectDirectoryService
        implements ProjectDirectoryService,
        AuthorizedPersonDirectoryService,
        DepartmentDirectoryService {

    private static final String DISPLAY_CHANNEL = "display";
    private static final String CANDIDATES_FACT = "subjectCandidates";
    private static final String TOTAL_COUNT_FACT = "totalCount";
    private static final String HAS_NEXT_FACT = "hasNext";
    private static final int MAX_TEXT_LENGTH = 512;
    private static final Pattern DATASET_CODE =
            Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private final ReportDatasetExecutionService executionService;
    private final DatasetExecutionProofVerifier proofVerifier;
    private final String projectDatasetCode;
    private final String personDatasetCode;
    private final String departmentDatasetCode;

    public WorkflowBackedSubjectDirectoryService(
            ReportDatasetExecutionService executionService,
            DatasetExecutionProofVerifier proofVerifier,
            @Value("${ai.business.subject.directory.project-dataset-code:}")
            String projectDatasetCode,
            @Value("${ai.business.subject.directory.person-dataset-code:}")
            String personDatasetCode,
            @Value("${ai.business.subject.directory.department-dataset-code:}")
            String departmentDatasetCode) {
        this.executionService = Objects.requireNonNull(
                executionService,
                "executionService不能为空"
        );
        this.proofVerifier = Objects.requireNonNull(
                proofVerifier,
                "proofVerifier不能为空"
        );
        this.projectDatasetCode = normalizeDatasetCode(projectDatasetCode);
        this.personDatasetCode = normalizeDatasetCode(personDatasetCode);
        this.departmentDatasetCode = normalizeDatasetCode(departmentDatasetCode);
    }

    /**
     * 一次请求只执行一个目录数据集，不会在项目列表或人员候选阶段扇出业务详情查询。
     */
    @Override
    public SubjectDirectoryPage search(SubjectDirectoryQuery query) {
        try {
            validateQuery(query);
            String datasetCode = datasetCode(query.subjectType());
            if (!StringUtils.hasText(datasetCode)) {
                return SubjectDirectoryPage.denied();
            }
            Map<String, Object> canonicalInput = canonicalInput(query);
            DatasetExecutionRequest request = new DatasetExecutionRequest(
                    query.agentRunId(),
                    query.userId(),
                    query.sessionId(),
                    query.authorization(),
                    query.secureContext(),
                    datasetCode,
                    query.subjectType(),
                    query.userId(),
                    canonicalInput
            );
            DatasetExecutionResult result = executionService.execute(request);
            return parseResult(query, datasetCode, canonicalInput, result);
        } catch (RuntimeException ignored) {
            // 目录配置、权限、执行证明或安全事实任一异常都失败关闭，不向对话泄露对象是否存在。
            return SubjectDirectoryPage.denied();
        }
    }

    private SubjectDirectoryPage parseResult(
            SubjectDirectoryQuery query,
            String datasetCode,
            Map<String, Object> canonicalInput,
            DatasetExecutionResult result) {
        if (result == null
                || !proofVerifier.verify(result)
                || !sourceMatches(
                        query,
                        datasetCode,
                        canonicalInput,
                        result.source()
                )) {
            return SubjectDirectoryPage.denied();
        }
        if (result.status() == DatasetExecutionStatus.EMPTY) {
            return SubjectDirectoryPage.empty(query.pageNumber(), query.pageSize());
        }
        if (result.status() != DatasetExecutionStatus.SUCCESS
                || !result.dataComplete()) {
            return SubjectDirectoryPage.denied();
        }
        Map<?, ?> display = requireMap(result.safeFacts().get(DISPLAY_CHANNEL));
        List<?> rawCandidates = requireList(display.get(CANDIDATES_FACT));
        if (rawCandidates.size() > query.pageSize()) {
            throw new IllegalArgumentException("主体候选数量超过当前页上限");
        }
        List<SubjectCandidate> candidates = parseCandidates(
                query.subjectType(),
                rawCandidates
        );
        long totalCount = requireNonNegativeLong(display.get(TOTAL_COUNT_FACT));
        boolean hasNext = requireBoolean(display.get(HAS_NEXT_FACT));
        validatePageMetadata(query, candidates.size(), totalCount, hasNext);
        return new SubjectDirectoryPage(
                true,
                candidates,
                query.pageNumber(),
                query.pageSize(),
                totalCount,
                hasNext
        );
    }

    private void validatePageMetadata(
            SubjectDirectoryQuery query,
            int candidateCount,
            long totalCount,
            boolean hasNext) {
        long offset = Math.multiplyExact(
                (long) query.pageNumber() - 1,
                query.pageSize()
        );
        long seen = Math.addExact(offset, candidateCount);
        if (candidateCount > 0 && offset >= totalCount) {
            throw new IllegalArgumentException("主体目录页码超过总数");
        }
        if (hasNext != (seen < totalCount)) {
            throw new IllegalArgumentException("主体目录翻页标志与总数不一致");
        }
    }

    private boolean sourceMatches(
            SubjectDirectoryQuery query,
            String datasetCode,
            Map<String, Object> canonicalInput,
            DatasetExecutionSource source) {
        String expectedInputHash = ContentHashUtils.sha256(
                ReportDatasetValidator.canonicalSafeValue(canonicalInput)
        );
        return source != null
                && query.userId().equals(source.userId())
                && query.sessionId().equals(source.sessionId())
                && query.subjectType() == source.subjectType()
                && query.userId().equals(source.subjectId())
                && datasetCode.equals(source.datasetCode())
                && expectedInputHash.equals(source.canonicalInputHash());
    }

    private List<SubjectCandidate> parseCandidates(
            BusinessSubjectType type,
            List<?> rawCandidates) {
        List<SubjectCandidate> candidates = new ArrayList<>(rawCandidates.size());
        for (Object rawCandidate : rawCandidates) {
            Map<?, ?> values = requireMap(rawCandidate);
            candidates.add(parseCandidate(type, values));
        }
        return List.copyOf(candidates);
    }

    private SubjectCandidate parseCandidate(
            BusinessSubjectType type,
            Map<?, ?> values) {
        String subjectId = requireText(values.get("subjectId"), "subjectId");
        String displayName = requireText(values.get("displayName"), "displayName");
        return switch (type) {
            case PROJECT -> new SubjectCandidate(
                    type,
                    subjectId,
                    displayName,
                    null,
                    null,
                    requireText(values.get("projectCode"), "projectCode"),
                    optionalText(values.get("projectType"))
            );
            case PERSON -> new SubjectCandidate(
                    type,
                    subjectId,
                    displayName,
                    optionalText(values.get("maskedEmployeeNo")),
                    optionalText(values.get("departmentPath")),
                    null,
                    null
            );
            case DEPARTMENT -> new SubjectCandidate(
                    type,
                    subjectId,
                    displayName,
                    null,
                    optionalText(values.get("departmentPath")),
                    null,
                    null
            );
        };
    }

    private Map<String, Object> canonicalInput(SubjectDirectoryQuery query) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("searchMode", query.searchMode().name());
        putIfPresent(input, "selectedSubjectId", query.selectedSubjectId());
        putIfPresent(input, "projectCode", query.projectCode());
        putIfPresent(input, "searchName", query.searchName());
        putIfPresent(input, "projectManager", query.projectManager());
        putIfPresent(input, "projectYear", query.projectYear());
        putIfPresent(input, "employeeNo", query.employeeNo());
        input.put("pageNumber", query.pageNumber());
        input.put("pageSize", query.pageSize());
        return Map.copyOf(input);
    }

    private void validateQuery(SubjectDirectoryQuery query) {
        if (query == null
                || !StringUtils.hasText(query.agentRunId())
                || !StringUtils.hasText(query.userId())
                || !StringUtils.hasText(query.sessionId())
                || !StringUtils.hasText(query.authorization())
                || query.subjectType() == null
                || query.searchMode() == null
                || query.pageNumber() < 1
                || query.pageSize() < 1
                || query.pageSize() > 200) {
            throw new IllegalArgumentException("主体目录请求不完整");
        }
    }

    private String datasetCode(BusinessSubjectType type) {
        return switch (type) {
            case PROJECT -> projectDatasetCode;
            case PERSON -> personDatasetCode;
            case DEPARTMENT -> departmentDatasetCode;
        };
    }

    private String normalizeDatasetCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        return DATASET_CODE.matcher(normalized).matches() ? normalized : null;
    }

    private Map<?, ?> requireMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("主体目录事实不是对象");
        }
        return map;
    }

    private List<?> requireList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("主体目录候选不是数组");
        }
        return list;
    }

    private long requireNonNegativeLong(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("主体目录总数不是数字");
        }
        long result = number.longValue();
        if (result < 0 || number.doubleValue() != result) {
            throw new IllegalArgumentException("主体目录总数不合法");
        }
        return result;
    }

    private boolean requireBoolean(Object value) {
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("主体目录翻页标志不合法");
        }
        return bool;
    }

    private String requireText(Object value, String field) {
        String text = optionalText(value);
        if (text == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return text;
    }

    private String optionalText(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)
                || !StringUtils.hasText(text)
                || text.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("主体目录文本字段不合法");
        }
        return text.trim();
    }

    private void putIfPresent(
            Map<String, Object> input,
            String key,
            Object value) {
        if (value != null) {
            input.put(key, value);
        }
    }
}
