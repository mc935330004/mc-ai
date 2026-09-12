package org.example.ai.agent.business.panorama;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.subject.ProjectDirectoryService;
import org.example.ai.agent.business.subject.SubjectDirectoryQuery;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.business.subject.model.SubjectSearchMode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * 将候选选择令牌重新绑定到来源系统当前授权目录中的唯一项目。
 */
@Service
public class ProjectSubjectAuthorizationService {

    private final SubjectSelectionTokenService tokenService;
    private final ProjectDirectoryService directoryService;

    public ProjectSubjectAuthorizationService(
            SubjectSelectionTokenService tokenService,
            ProjectDirectoryService directoryService) {
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService不能为空");
        this.directoryService = Objects.requireNonNull(
                directoryService,
                "directoryService不能为空"
        );
    }

    /**
     * 令牌只负责恢复内部项目ID；项目编码和类型必须以本次目录复核结果为准。
     */
    public AuthorizedProjectSubject authorize(ProjectPanoramaCommand command) {
        if (command == null || !StringUtils.hasText(command.projectSelectionToken())) {
            throw new IllegalArgumentException("项目选择令牌不能为空");
        }
        String projectId = tokenService.resolve(
                        command.projectSelectionToken(),
                        command.userId(),
                        command.sessionId(),
                        BusinessSubjectType.PROJECT
                )
                .orElseThrow(() -> new IllegalArgumentException("项目选择已失效"));
        SubjectDirectoryPage page = directoryService.search(new SubjectDirectoryQuery(
                command.agentRunId(),
                command.userId(),
                command.sessionId(),
                command.authorization(),
                command.secureContext(),
                BusinessSubjectType.PROJECT,
                SubjectSearchMode.SELECTED_SUBJECT,
                projectId,
                null,
                null,
                null,
                null,
                null,
                1,
                1
        ));
        AuthorizedSubjectCandidate candidate = uniqueCandidate(page, projectId);
        if (!StringUtils.hasText(candidate.projectCode())
                || !StringUtils.hasText(candidate.projectType())) {
            throw new IllegalStateException("授权项目缺少编码或类型");
        }
        return new AuthorizedProjectSubject(
                candidate.rawSubjectId(),
                candidate.projectCode(),
                candidate.projectType(),
                candidate.displayName()
        );
    }

    private AuthorizedSubjectCandidate uniqueCandidate(
            SubjectDirectoryPage page,
            String projectId) {
        if (page == null
                || !page.accessible()
                || page.totalCount() != 1
                || page.hasNext()
                || page.candidates().size() != 1) {
            throw new IllegalArgumentException("项目当前无权访问或无法唯一定位");
        }
        AuthorizedSubjectCandidate candidate = page.candidates().get(0);
        if (candidate == null
                || candidate.type() != BusinessSubjectType.PROJECT
                || !projectId.equals(candidate.rawSubjectId())) {
            throw new IllegalArgumentException("项目授权目录结果不一致");
        }
        return candidate;
    }
}
