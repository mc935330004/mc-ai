package org.example.ai.agent.business.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.subject.AuthorizedPersonDirectoryService;
import org.example.ai.agent.business.subject.DepartmentDirectoryService;
import org.example.ai.agent.business.subject.ProjectDirectoryService;
import org.example.ai.agent.business.subject.SubjectSelectionTokenService;
import org.example.ai.agent.business.subject.model.AuthorizedSubjectCandidate;
import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;
import org.example.ai.agent.chat.protocol.block.TextBlock;
import org.example.ai.agent.chat.protocol.response.AiResponse;
import org.example.ai.agent.chat.protocol.response.ResponseContext;
import org.example.ai.agent.chat.protocol.response.ResponseMeta;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;
import org.example.ai.agent.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BusinessResponseAccessServiceTest {

    @Test
    void bindingIsStoredPrivatelyAndRemovedAfterCurrentPermissionCheck() {
        Fixture fixture = new Fixture();
        when(fixture.tokenService.resolve(
                "selection-token", "user-1", "conversation-1", BusinessSubjectType.PROJECT
        )).thenReturn(Optional.of("raw-project-id"));
        when(fixture.projectDirectory.search(any())).thenReturn(authorizedProject());

        String stored = fixture.service.bind(
                response(context("selection-token")), "user-1", "conversation-1"
        );
        String external = fixture.service.authorizeAndSanitize(
                stored, "user-1", "conversation-1", "run-1", "Bearer current-token"
        );

        assertThat(stored).contains("\"_access\"", "raw-project-id")
                .doesNotContain("selection-token");
        assertThat(external).contains("P-1001", "最终回答")
                .doesNotContain("_access", "raw-project-id", "selection-token");
    }

    @Test
    void revokedPermissionRejectsStoredBusinessResponse() {
        Fixture fixture = new Fixture();
        when(fixture.tokenService.resolve(
                "selection-token", "user-1", "conversation-1", BusinessSubjectType.PROJECT
        )).thenReturn(Optional.of("raw-project-id"));
        when(fixture.projectDirectory.search(any())).thenReturn(SubjectDirectoryPage.denied());
        String stored = fixture.service.bind(
                response(context("selection-token")), "user-1", "conversation-1"
        );

        assertThatThrownBy(() -> fixture.service.authorizeAndSanitize(
                stored, "user-1", "conversation-1", "run-1", "Bearer current-token"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("回答不存在或无权访问");
    }

    @Test
    void businessResponseWithoutPrivateBindingFailsClosed() throws Exception {
        Fixture fixture = new Fixture();
        String stored = fixture.objectMapper.writeValueAsString(response(
                new ResponseContext("PROJECT", "P-1001", "示例项目", "当前范围", null, null, null)
        ));

        assertThatThrownBy(() -> fixture.service.authorizeAndSanitize(
                stored, "user-1", "conversation-1", "run-1", "Bearer current-token"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("回答不存在或无权访问");
    }

    @Test
    void nonBusinessResponseNeverReturnsUnexpectedPrivateBinding() {
        Fixture fixture = new Fixture();
        String stored = """
                {"schemaVersion":2,"responseId":"response-1","runId":"run-1",
                "conversationId":"conversation-1","mode":"CHAT","status":"COMPLETED",
                "dataComplete":true,"context":null,"blocks":[],"references":[],"meta":{},
                "_access":{"subjectType":"PROJECT","subjectId":"must-not-leak"}}
                """;

        String external = fixture.service.authorizeAndSanitize(
                stored, "user-1", "conversation-1", "run-1", "Bearer current-token"
        );

        assertThat(external).doesNotContain("_access", "must-not-leak");
    }

    private ResponseContext context(String selectionToken) {
        return new ResponseContext(
                "PROJECT", "P-1001", "示例项目", "当前范围",
                null, null, null, selectionToken
        );
    }

    private AiResponse response(ResponseContext context) {
        return new AiResponse(
                AiResponse.CURRENT_SCHEMA_VERSION,
                "response-1",
                "run-1",
                "conversation-1",
                PresentationMode.CHAT,
                ResponseStatus.COMPLETED,
                true,
                context,
                List.of(new TextBlock(
                        "answer", "", 0, BlockStatus.READY,
                        BlockSource.SYSTEM, "最终回答"
                )),
                List.of(),
                ResponseMeta.empty()
        );
    }

    private SubjectDirectoryPage authorizedProject() {
        return new SubjectDirectoryPage(
                true,
                List.of(new AuthorizedSubjectCandidate(
                        BusinessSubjectType.PROJECT,
                        "raw-project-id",
                        "示例项目",
                        null,
                        null,
                        "P-1001",
                        "DELIVERY"
                )),
                1,
                2,
                1,
                true,
                false
        );
    }

    private static final class Fixture {
        private final SubjectSelectionTokenService tokenService = mock(SubjectSelectionTokenService.class);
        private final ProjectDirectoryService projectDirectory = mock(ProjectDirectoryService.class);
        private final AuthorizedPersonDirectoryService personDirectory = mock(AuthorizedPersonDirectoryService.class);
        private final DepartmentDirectoryService departmentDirectory = mock(DepartmentDirectoryService.class);
        private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        private final BusinessResponseAccessService service = new BusinessResponseAccessService(
                tokenService, projectDirectory, personDirectory, departmentDirectory, objectMapper
        );
    }
}
