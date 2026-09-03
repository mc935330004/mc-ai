package org.example.ai.agent.business.panorama;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.panorama.model.AuthorizedProjectSubject;
import org.example.ai.agent.business.panorama.model.ProjectPanoramaCommand;
import org.example.ai.agent.business.subject.ProjectDirectoryService;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectSubjectAuthorizationServiceTest {

    @Test
    void bindsProjectIdCodeAndTypeToCurrentAuthorizedDirectoryResult() {
        SubjectSelectionTokenService tokenService = mock(SubjectSelectionTokenService.class);
        ProjectDirectoryService directoryService = mock(ProjectDirectoryService.class);
        when(tokenService.resolve("selection-token", "user-1", "session-1", BusinessSubjectType.PROJECT))
                .thenReturn(Optional.of("project-id-1"));
        when(directoryService.search(any())).thenReturn(new SubjectDirectoryPage(
                true,
                List.of(new AuthorizedSubjectCandidate(
                        BusinessSubjectType.PROJECT,
                        "project-id-1",
                        "一号项目",
                        null,
                        null,
                        "XXXT2674040",
                        "工程项目"
                )),
                1,
                1,
                1,
                false
        ));

        AuthorizedProjectSubject subject = new ProjectSubjectAuthorizationService(
                tokenService,
                directoryService
        ).authorize(command());

        assertThat(subject.projectId()).isEqualTo("project-id-1");
        assertThat(subject.projectCode()).isEqualTo("XXXT2674040");
        assertThat(subject.projectType()).isEqualTo("工程项目");
    }

    @Test
    void rejectsExpiredTokenOrNonUniqueCurrentAuthorization() {
        SubjectSelectionTokenService tokenService = mock(SubjectSelectionTokenService.class);
        ProjectDirectoryService directoryService = mock(ProjectDirectoryService.class);
        when(tokenService.resolve(any(), any(), any(), any())).thenReturn(Optional.empty());
        ProjectSubjectAuthorizationService service = new ProjectSubjectAuthorizationService(
                tokenService,
                directoryService
        );

        assertThatThrownBy(() -> service.authorize(command()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ProjectPanoramaCommand command() {
        return new ProjectPanoramaCommand(
                "run-1",
                "user-1",
                "session-1",
                "Bearer token",
                Map.of("tenant", "t1"),
                "selection-token",
                Map.of()
        );
    }
}
