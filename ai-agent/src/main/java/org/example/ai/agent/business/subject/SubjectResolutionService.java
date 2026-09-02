package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.subject.model.SubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectResolutionRequest;
import org.example.ai.agent.business.subject.model.SubjectResolutionResult;
import org.example.ai.agent.business.subject.model.SubjectResolutionState;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 确定性主体定位状态机。
 *
 * 模型只提供名称、编码等搜索提示；是否授权、是否唯一以及最终主体均由来源系统目录和本服务决定。
 */
@Service
public class SubjectResolutionService {

    private static final String SAFE_NOT_FOUND = "无法定位或无权访问该主体";
    private static final String SAFE_CANDIDATES = "请从当前授权范围内选择主体";
    private static final String SAFE_RESOLVED = "主体已定位";
    private static final int MAX_PAGE_SIZE = 200;

    private final ProjectDirectoryService projectDirectoryService;
    private final AuthorizedPersonDirectoryService personDirectoryService;
    private final DepartmentDirectoryService departmentDirectoryService;
    private final Clock clock;

    @Autowired
    public SubjectResolutionService(
            ProjectDirectoryService projectDirectoryService,
            AuthorizedPersonDirectoryService personDirectoryService,
            DepartmentDirectoryService departmentDirectoryService) {
        this(
                projectDirectoryService,
                personDirectoryService,
                departmentDirectoryService,
                Clock.systemDefaultZone()
        );
    }

    SubjectResolutionService(
            ProjectDirectoryService projectDirectoryService,
            AuthorizedPersonDirectoryService personDirectoryService,
            DepartmentDirectoryService departmentDirectoryService,
            Clock clock) {
        this.projectDirectoryService = Objects.requireNonNull(
                projectDirectoryService,
                "projectDirectoryService不能为空"
        );
        this.personDirectoryService = Objects.requireNonNull(
                personDirectoryService,
                "personDirectoryService不能为空"
        );
        this.departmentDirectoryService = Objects.requireNonNull(
                departmentDirectoryService,
                "departmentDirectoryService不能为空"
        );
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
    }

    /**
     * 每次定位都重新调用来源系统目录，不信任模型或会话中的旧权限结论。
     */
    public SubjectResolutionResult resolve(SubjectResolutionRequest request) {
        validateRequest(request);
        SubjectDirectoryQuery query = buildQuery(request);
        SubjectDirectoryPage page = switch (request.subjectType()) {
            case PROJECT -> projectDirectoryService.search(query);
            case PERSON -> personDirectoryService.search(query);
            case DEPARTMENT -> departmentDirectoryService.search(query);
        };
        return decide(query, page);
    }

    private SubjectDirectoryQuery buildQuery(SubjectResolutionRequest request) {
        SubjectSearchMode mode = resolveMode(request);
        Integer projectYear = request.projectYear();
        if (mode == SubjectSearchMode.MY_PROJECTS && projectYear == null) {
            projectYear = LocalDate.now(clock).getYear();
        }
        return new SubjectDirectoryQuery(
                request.agentRunId(),
                request.userId(),
                request.sessionId(),
                request.authorization(),
                request.secureContext(),
                request.subjectType(),
                mode,
                trim(request.selectedSubjectId()),
                trim(request.projectCode()),
                trim(request.searchName()),
                trim(request.projectManager()),
                projectYear,
                trim(request.employeeNo()),
                request.pageNumber(),
                request.pageSize()
        );
    }

    private SubjectSearchMode resolveMode(SubjectResolutionRequest request) {
        if (StringUtils.hasText(request.selectedSubjectId())) {
            return SubjectSearchMode.SELECTED_SUBJECT;
        }
        return switch (request.subjectType()) {
            case PROJECT -> projectMode(request);
            case PERSON -> personMode(request);
            case DEPARTMENT -> departmentMode(request);
        };
    }

    private SubjectSearchMode projectMode(SubjectResolutionRequest request) {
        if (request.myProjects()) {
            return SubjectSearchMode.MY_PROJECTS;
        }
        if (StringUtils.hasText(request.projectCode())) {
            return SubjectSearchMode.PROJECT_CODE;
        }
        if (StringUtils.hasText(request.projectManager())) {
            return SubjectSearchMode.PROJECT_MANAGER;
        }
        if (StringUtils.hasText(request.searchName())) {
            return SubjectSearchMode.PROJECT_NAME;
        }
        throw new IllegalArgumentException("项目主体缺少可用定位提示");
    }

    private SubjectSearchMode personMode(SubjectResolutionRequest request) {
        if (StringUtils.hasText(request.employeeNo())) {
            return SubjectSearchMode.EMPLOYEE_NO;
        }
        if (StringUtils.hasText(request.searchName())) {
            return SubjectSearchMode.PERSON_NAME;
        }
        return SubjectSearchMode.CURRENT_PERSON;
    }

    private SubjectSearchMode departmentMode(SubjectResolutionRequest request) {
        if (StringUtils.hasText(request.searchName())) {
            return SubjectSearchMode.DEPARTMENT_NAME;
        }
        throw new IllegalArgumentException("部门主体缺少可用定位提示");
    }

    private SubjectResolutionResult decide(
            SubjectDirectoryQuery query,
            SubjectDirectoryPage page) {
        if (page == null || !page.accessible()) {
            return terminal(SubjectResolutionState.DENIED, query);
        }
        if (page.candidates().isEmpty()) {
            return terminal(SubjectResolutionState.EMPTY, query);
        }
        if (page.candidates().stream().anyMatch(candidate ->
                candidate == null || candidate.type() != query.subjectType())) {
            return terminal(SubjectResolutionState.DENIED, query);
        }
        if (!exactCandidateMatches(query, page.candidates())) {
            return terminal(SubjectResolutionState.EMPTY, query);
        }
        if (query.searchMode() == SubjectSearchMode.CURRENT_PERSON
                && (page.candidates().size() != 1
                || page.totalCount() != 1
                || page.hasNext())) {
            return terminal(SubjectResolutionState.DENIED, query);
        }
        if (mustReturnCandidates(query.searchMode())
                || page.candidates().size() > 1
                || page.totalCount() != 1
                || page.hasNext()) {
            return candidates(page);
        }
        return resolved(page.candidates().get(0), page);
    }

    private boolean exactCandidateMatches(
            SubjectDirectoryQuery query,
            List<SubjectCandidate> candidates) {
        if (query.searchMode() == SubjectSearchMode.SELECTED_SUBJECT) {
            return candidates.stream().allMatch(candidate ->
                    candidate.subjectId().equals(query.selectedSubjectId())
            );
        }
        if (query.searchMode() == SubjectSearchMode.PROJECT_CODE) {
            return candidates.stream().allMatch(candidate ->
                    candidate.projectCode() != null
                            && candidate.projectCode().equalsIgnoreCase(query.projectCode())
            );
        }
        return true;
    }

    private boolean mustReturnCandidates(SubjectSearchMode mode) {
        return mode == SubjectSearchMode.MY_PROJECTS
                || mode == SubjectSearchMode.PROJECT_NAME
                || mode == SubjectSearchMode.PROJECT_MANAGER;
    }

    private SubjectResolutionResult resolved(
            SubjectCandidate subject,
            SubjectDirectoryPage page) {
        return new SubjectResolutionResult(
                SubjectResolutionState.RESOLVED,
                subject,
                List.of(),
                page.pageNumber(),
                page.pageSize(),
                page.totalCount(),
                page.hasNext(),
                SAFE_RESOLVED
        );
    }

    private SubjectResolutionResult candidates(SubjectDirectoryPage page) {
        return new SubjectResolutionResult(
                SubjectResolutionState.CANDIDATES,
                null,
                page.candidates(),
                page.pageNumber(),
                page.pageSize(),
                page.totalCount(),
                page.hasNext(),
                SAFE_CANDIDATES
        );
    }

    private SubjectResolutionResult terminal(
            SubjectResolutionState state,
            SubjectDirectoryQuery query) {
        return new SubjectResolutionResult(
                state,
                null,
                List.of(),
                query.pageNumber(),
                query.pageSize(),
                0,
                false,
                SAFE_NOT_FOUND
        );
    }

    private void validateRequest(SubjectResolutionRequest request) {
        if (request == null
                || !StringUtils.hasText(request.agentRunId())
                || !StringUtils.hasText(request.userId())
                || !StringUtils.hasText(request.sessionId())
                || !StringUtils.hasText(request.authorization())
                || request.subjectType() == null) {
            throw new IllegalArgumentException("主体定位请求不完整");
        }
        if (request.pageNumber() < 1
                || request.pageSize() < 1
                || request.pageSize() > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("主体目录分页参数不合法");
        }
        if (request.projectYear() != null
                && (request.projectYear() < 2000 || request.projectYear() > 9999)) {
            throw new IllegalArgumentException("项目年度不合法");
        }
        validateSubjectHints(request);
    }

    private void validateSubjectHints(SubjectResolutionRequest request) {
        if (StringUtils.hasText(request.selectedSubjectId())
                && (StringUtils.hasText(request.projectCode())
                || StringUtils.hasText(request.searchName())
                || StringUtils.hasText(request.projectManager())
                || StringUtils.hasText(request.employeeNo())
                || request.myProjects())) {
            throw new IllegalArgumentException("已选主体不能同时携带搜索提示");
        }
        if (request.subjectType() == BusinessSubjectType.PROJECT
                && StringUtils.hasText(request.employeeNo())) {
            throw new IllegalArgumentException("项目主体不能携带员工工号");
        }
        if (request.subjectType() != BusinessSubjectType.PROJECT
                && (StringUtils.hasText(request.projectCode())
                || StringUtils.hasText(request.projectManager())
                || request.projectYear() != null
                || request.myProjects())) {
            throw new IllegalArgumentException("非项目主体不能携带项目定位提示");
        }
        if (request.subjectType() == BusinessSubjectType.DEPARTMENT
                && StringUtils.hasText(request.employeeNo())) {
            throw new IllegalArgumentException("部门主体不能携带员工工号");
        }
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
