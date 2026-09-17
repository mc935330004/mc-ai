package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectResolutionRequest;
import org.example.ai.agent.business.subject.model.SubjectResolutionResult;
import org.example.ai.agent.business.subject.model.SubjectResolutionState;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.example.ai.agent.common.model.ProjectListScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 确定性主体定位状态机。
 *
 * 模型只提供名称、编码等搜索提示。
 * 主体权限、唯一性和最终主体由来源系统目录决定。
 */
@Service
public class SubjectResolutionService {

    private static final String SAFE_NOT_FOUND =
            "无法定位或无权访问该主体";

    private static final String SAFE_CANDIDATES =
            "请从当前授权范围内选择主体";

    private static final String SAFE_RESOLVED =
            "主体已定位";

    private static final int MAX_PAGE_SIZE = 200;

    private final ProjectDirectoryService projectDirectoryService;
    private final AuthorizedPersonDirectoryService
            personDirectoryService;
    private final DepartmentDirectoryService
            departmentDirectoryService;
    private final SubjectSelectionTokenService
            selectionTokenService;
    private final Clock clock;

    @Autowired
    public SubjectResolutionService(
            ProjectDirectoryService projectDirectoryService,
            AuthorizedPersonDirectoryService
                    personDirectoryService,
            DepartmentDirectoryService
                    departmentDirectoryService,
            SubjectSelectionTokenService
                    selectionTokenService
    ) {
        this(
                projectDirectoryService,
                personDirectoryService,
                departmentDirectoryService,
                selectionTokenService,
                Clock.systemDefaultZone()
        );
    }

    SubjectResolutionService(
            ProjectDirectoryService projectDirectoryService,
            AuthorizedPersonDirectoryService
                    personDirectoryService,
            DepartmentDirectoryService
                    departmentDirectoryService,
            SubjectSelectionTokenService
                    selectionTokenService,
            Clock clock
    ) {
        this.projectDirectoryService =
                Objects.requireNonNull(
                        projectDirectoryService,
                        "projectDirectoryService不能为空"
                );

        this.personDirectoryService =
                Objects.requireNonNull(
                        personDirectoryService,
                        "personDirectoryService不能为空"
                );

        this.departmentDirectoryService =
                Objects.requireNonNull(
                        departmentDirectoryService,
                        "departmentDirectoryService不能为空"
                );

        this.selectionTokenService =
                Objects.requireNonNull(
                        selectionTokenService,
                        "selectionTokenService不能为空"
                );

        this.clock = Objects.requireNonNull(
                clock,
                "clock不能为空"
        );
    }

    /**
     * 每次定位都重新调用来源系统目录。
     *
     * 会话和前端中的旧权限结论不能作为当前权限依据。
     */
    public SubjectResolutionResult resolve(
            SubjectResolutionRequest request
    ) {
        validateRequest(request);

        Optional<String> selectedSubjectId =
                selectedSubjectId(request);

        if (StringUtils.hasText(request.selectionToken())
                && selectedSubjectId.isEmpty()) {
            return terminal(
                    SubjectResolutionState.DENIED,
                    request.pageNumber(),
                    request.pageSize()
            );
        }

        SubjectDirectoryQuery query = buildQuery(
                request,
                selectedSubjectId.orElse(null)
        );

        SubjectDirectoryPage page =
                switch (request.subjectType()) {
                    case PROJECT ->
                            projectDirectoryService.search(query);
                    case PERSON ->
                            personDirectoryService.search(query);
                    case DEPARTMENT ->
                            departmentDirectoryService.search(query);
                };

        return decide(query, page);
    }

    /**
     * 解析并校验选择凭证。
     */
    private Optional<String> selectedSubjectId(
            SubjectResolutionRequest request
    ) {
        if (!StringUtils.hasText(
                request.selectionToken()
        )) {
            return Optional.empty();
        }

        return selectionTokenService.resolve(
                request.selectionToken(),
                request.userId(),
                request.sessionId(),
                request.subjectType()
        );
    }

    /**
     * 构建来源系统目录查询。
     */
    private SubjectDirectoryQuery buildQuery(
            SubjectResolutionRequest request,
            String selectedSubjectId
    ) {
        SubjectSearchMode mode = resolveMode(request);
        Integer projectYear = request.projectYear();

        // 项目列表默认使用当前年度。
        if (isProjectListMode(mode)
                && projectYear == null) {
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
                selectedSubjectId,
                trim(request.projectCode()),
                trim(request.searchName()),
                trim(request.projectManager()),
                projectYear,
                trim(request.employeeNo()),
                request.pageNumber(),
                request.pageSize()
        );
    }

    /**
     * 根据后端受控请求确定查询模式。
     */
    private SubjectSearchMode resolveMode(
            SubjectResolutionRequest request
    ) {
        if (StringUtils.hasText(
                request.selectionToken()
        )) {
            return SubjectSearchMode.SELECTED_SUBJECT;
        }

        return switch (request.subjectType()) {
            case PROJECT -> projectMode(request);
            case PERSON -> personMode(request);
            case DEPARTMENT -> departmentMode(request);
        };
    }

    /**
     * 确定项目目录查询模式。
     */
    private SubjectSearchMode projectMode(
            SubjectResolutionRequest request
    ) {
        if (request.projectListScope()
                == ProjectListScope.MY_PROJECTS) {
            return SubjectSearchMode.MY_PROJECTS;
        }

        if (request.projectListScope()
                == ProjectListScope.VIEWABLE_PROJECTS) {
            return SubjectSearchMode.VIEWABLE_PROJECTS;
        }

        if (StringUtils.hasText(
                request.projectCode()
        )) {
            return SubjectSearchMode.PROJECT_CODE;
        }

        if (StringUtils.hasText(
                request.projectManager()
        )) {
            return SubjectSearchMode.PROJECT_MANAGER;
        }

        if (StringUtils.hasText(
                request.searchName()
        )) {
            return SubjectSearchMode.PROJECT_NAME;
        }

        throw new IllegalArgumentException(
                "项目主体缺少可用定位提示"
        );
    }

    /**
     * 确定人员目录查询模式。
     */
    private SubjectSearchMode personMode(
            SubjectResolutionRequest request
    ) {
        if (StringUtils.hasText(
                request.employeeNo()
        )) {
            return SubjectSearchMode.EMPLOYEE_NO;
        }

        if (StringUtils.hasText(
                request.searchName()
        )) {
            return SubjectSearchMode.PERSON_NAME;
        }

        return SubjectSearchMode.CURRENT_PERSON;
    }

    /**
     * 确定部门目录查询模式。
     */
    private SubjectSearchMode departmentMode(
            SubjectResolutionRequest request
    ) {
        if (StringUtils.hasText(
                request.searchName()
        )) {
            return SubjectSearchMode.DEPARTMENT_NAME;
        }

        throw new IllegalArgumentException(
                "部门主体缺少可用定位提示"
        );
    }

    /**
     * 根据目录结果确定最终状态。
     */
    private SubjectResolutionResult decide(
            SubjectDirectoryQuery query,
            SubjectDirectoryPage page
    ) {
        if (page == null || !page.accessible()) {
            return terminal(
                    SubjectResolutionState.DENIED,
                    query
            );
        }

        if (page.candidates().isEmpty()) {
            return terminal(
                    SubjectResolutionState.EMPTY,
                    query
            );
        }

        boolean invalidType =
                page.candidates().stream()
                        .anyMatch(candidate ->
                                candidate == null
                                        || candidate.type()
                                        != query.subjectType()
                        );

        if (invalidType) {
            return terminal(
                    SubjectResolutionState.DENIED,
                    query
            );
        }

        if (!exactCandidateMatches(
                query,
                page.candidates()
        )) {
            return terminal(
                    SubjectResolutionState.EMPTY,
                    query
            );
        }

        if (requiresStrictUniqueResult(
                query.searchMode()
        ) && !isKnownUnique(page)) {
            return terminal(
                    SubjectResolutionState.DENIED,
                    query
            );
        }

        if (mustReturnCandidates(
                query.searchMode()
        )
                || !page.totalKnown()
                || page.candidates().size() > 1
                || page.totalCount() != 1
                || page.hasNext()) {
            return candidates(page, query);
        }

        return resolved(
                page.candidates().get(0),
                page,
                query
        );
    }

    /**
     * 当前人员和选择凭证复验必须得到唯一结果。
     */
    private boolean requiresStrictUniqueResult(
            SubjectSearchMode mode
    ) {
        return mode == SubjectSearchMode.CURRENT_PERSON
                || mode
                == SubjectSearchMode.SELECTED_SUBJECT;
    }

    /**
     * 判断目录是否明确返回唯一结果。
     */
    private boolean isKnownUnique(
            SubjectDirectoryPage page
    ) {
        return page.totalKnown()
                && page.totalCount() == 1
                && page.candidates().size() == 1
                && !page.hasNext();
    }

    /**
     * 校验精确定位结果与查询条件一致。
     */
    private boolean exactCandidateMatches(
            SubjectDirectoryQuery query,
            List<AuthorizedSubjectCandidate> candidates
    ) {
        if (query.searchMode()
                == SubjectSearchMode.SELECTED_SUBJECT) {
            return candidates.stream()
                    .allMatch(candidate ->
                            candidate.rawSubjectId()
                                    .equals(
                                            query.selectedSubjectId()
                                    )
                    );
        }

        if (query.searchMode()
                == SubjectSearchMode.PROJECT_CODE) {
            return candidates.stream()
                    .allMatch(candidate ->
                            candidate.projectCode() != null
                                    && candidate.projectCode()
                                    .equalsIgnoreCase(
                                            query.projectCode()
                                    )
                    );
        }

        return true;
    }

    /**
     * 项目列表和范围查询必须返回候选列表。
     */
    private boolean mustReturnCandidates(
            SubjectSearchMode mode
    ) {
        return mode == SubjectSearchMode.MY_PROJECTS
                || mode
                == SubjectSearchMode.VIEWABLE_PROJECTS
                || mode
                == SubjectSearchMode.PROJECT_MANAGER;
    }

    /**
     * 判断是否为项目列表查询。
     */
    private boolean isProjectListMode(
            SubjectSearchMode mode
    ) {
        return mode == SubjectSearchMode.MY_PROJECTS
                || mode
                == SubjectSearchMode.VIEWABLE_PROJECTS;
    }

    /**
     * 构建唯一主体结果。
     */
    private SubjectResolutionResult resolved(
            AuthorizedSubjectCandidate subject,
            SubjectDirectoryPage page,
            SubjectDirectoryQuery query
    ) {
        return new SubjectResolutionResult(
                SubjectResolutionState.RESOLVED,
                externalCandidate(subject, query),
                List.of(),
                page.pageNumber(),
                page.pageSize(),
                page.totalCount(),
                page.totalKnown(),
                page.hasNext(),
                SAFE_RESOLVED
        );
    }

    /**
     * 构建主体候选结果。
     */
    private SubjectResolutionResult candidates(
            SubjectDirectoryPage page,
            SubjectDirectoryQuery query
    ) {
        List<SubjectCandidate> external =
                page.candidates().stream()
                        .map(candidate ->
                                externalCandidate(
                                        candidate,
                                        query
                                )
                        )
                        .toList();

        return new SubjectResolutionResult(
                SubjectResolutionState.CANDIDATES,
                null,
                external,
                page.pageNumber(),
                page.pageSize(),
                page.totalCount(),
                page.totalKnown(),
                page.hasNext(),
                SAFE_CANDIDATES
        );
    }

    /**
     * 构建拒绝或空结果。
     */
    private SubjectResolutionResult terminal(
            SubjectResolutionState state,
            SubjectDirectoryQuery query
    ) {
        return terminal(
                state,
                query.pageNumber(),
                query.pageSize()
        );
    }

    /**
     * 构建不携带候选信息的终止结果。
     */
    private SubjectResolutionResult terminal(
            SubjectResolutionState state,
            int pageNumber,
            int pageSize
    ) {
        return new SubjectResolutionResult(
                state,
                null,
                List.of(),
                pageNumber,
                pageSize,
                0,
                true,
                false,
                SAFE_NOT_FOUND
        );
    }

    /**
     * 为授权候选生成当前用户和会话绑定的选择凭证。
     */
    private SubjectCandidate externalCandidate(
            AuthorizedSubjectCandidate candidate,
            SubjectDirectoryQuery query
    ) {
        String token = selectionTokenService.issue(
                candidate.rawSubjectId(),
                query.userId(),
                query.sessionId(),
                candidate.type()
        );

        return new SubjectCandidate(
                candidate.type(),
                token,
                candidate.displayName(),
                candidate.maskedEmployeeNo(),
                candidate.departmentPath(),
                candidate.projectCode(),
                candidate.projectType(),
                candidate.projectRelationship(),
                candidate.projectStatus()
        );
    }

    /**
     * 校验主体定位请求。
     */
    private void validateRequest(
            SubjectResolutionRequest request
    ) {
        if (request == null
                || !StringUtils.hasText(
                request.agentRunId()
        )
                || !StringUtils.hasText(
                request.userId()
        )
                || !StringUtils.hasText(
                request.sessionId()
        )
                || !StringUtils.hasText(
                request.authorization()
        )
                || request.subjectType() == null) {
            throw new IllegalArgumentException(
                    "主体定位请求不完整"
            );
        }

        SubjectRequestLimits.requireText(
                request.agentRunId(),
                "agentRunId",
                128
        );

        SubjectRequestLimits.requireText(
                request.userId(),
                "userId",
                256
        );

        SubjectRequestLimits.requireText(
                request.sessionId(),
                "sessionId",
                256
        );

        SubjectRequestLimits.requireText(
                request.authorization(),
                "authorization",
                32768
        );

        SubjectRequestLimits.optionalText(
                request.selectionToken(),
                "selectionToken",
                4096
        );

        SubjectRequestLimits.optionalText(
                request.projectCode(),
                "projectCode",
                128
        );

        SubjectRequestLimits.optionalText(
                request.searchName(),
                "searchName",
                256
        );

        SubjectRequestLimits.optionalText(
                request.projectManager(),
                "projectManager",
                256
        );

        SubjectRequestLimits.optionalText(
                request.employeeNo(),
                "employeeNo",
                128
        );

        if (request.pageNumber() < 1
                || request.pageSize() < 1
                || request.pageSize()
                > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "主体目录分页参数不合法"
            );
        }

        if (request.projectYear() != null
                && (request.projectYear() < 1900
                || request.projectYear()
                > LocalDate.now(clock).getYear() + 1)) {
            throw new IllegalArgumentException(
                    "项目年度不合法"
            );
        }

        validateSubjectHints(request);
    }

    /**
     * 校验不同主体类型的查询提示。
     */
    private void validateSubjectHints(
            SubjectResolutionRequest request
    ) {
        if (StringUtils.hasText(
                request.selectionToken()
        )
                && (StringUtils.hasText(
                request.projectCode()
        )
                || StringUtils.hasText(
                request.searchName()
        )
                || StringUtils.hasText(
                request.projectManager()
        )
                || StringUtils.hasText(
                request.employeeNo()
        )
                || request.projectListScope() != null)) {
            throw new IllegalArgumentException(
                    "已选主体不能同时携带搜索提示"
            );
        }

        if (request.projectListScope() != null
                && request.subjectType()
                != BusinessSubjectType.PROJECT) {
            throw new IllegalArgumentException(
                    "projectListScope 仅支持项目主体"
            );
        }

        if (request.projectListScope() != null
                && hasProjectLocator(request)) {
            throw new IllegalArgumentException(
                    "项目列表范围不能与项目定位条件同时使用"
            );
        }

        if (request.subjectType()
                == BusinessSubjectType.PROJECT
                && StringUtils.hasText(
                request.employeeNo()
        )) {
            throw new IllegalArgumentException(
                    "项目主体不能携带员工工号"
            );
        }

        if (request.subjectType()
                != BusinessSubjectType.PROJECT
                && (StringUtils.hasText(
                request.projectCode()
        )
                || StringUtils.hasText(
                request.projectManager()
        )
                || request.projectYear() != null
                || request.projectListScope() != null)) {
            throw new IllegalArgumentException(
                    "非项目主体不能携带项目定位提示"
            );
        }

        if (request.subjectType()
                == BusinessSubjectType.DEPARTMENT
                && StringUtils.hasText(
                request.employeeNo()
        )) {
            throw new IllegalArgumentException(
                    "部门主体不能携带员工工号"
            );
        }
    }

    /**
     * 判断是否包含单个项目定位条件。
     */
    private boolean hasProjectLocator(
            SubjectResolutionRequest request
    ) {
        return StringUtils.hasText(
                request.projectCode()
        )
                || StringUtils.hasText(
                request.searchName()
        )
                || StringUtils.hasText(
                request.projectManager()
        )
                || StringUtils.hasText(
                request.employeeNo()
        );
    }

    /**
     * 清理可选文本。
     */
    private String trim(String value) {
        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }
}