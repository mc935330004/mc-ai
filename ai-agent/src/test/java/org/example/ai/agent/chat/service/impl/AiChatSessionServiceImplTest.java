package org.example.ai.agent.chat.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.business.security.BusinessResponseAccessService;
import org.example.ai.agent.chat.entity.AiChatMessage;
import org.example.ai.agent.chat.entity.AiChatSession;
import org.example.ai.agent.chat.mapper.AiChatMessageMapper;
import org.example.ai.agent.chat.mapper.AiChatSessionMapper;
import org.example.ai.agent.chat.memory.service.ConversationStateService;
import org.example.ai.agent.chat.stream.ResponseChecksumService;
import org.example.ai.agent.chat.support.ActiveAgentRunRegistry;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.modelconfig.service.ChatModelPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiChatSessionServiceImplTest {

    private final AiChatSessionMapper sessionMapper = mock(AiChatSessionMapper.class);
    private final AiChatMessageMapper messageMapper = mock(AiChatMessageMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ChatModelPolicyService modelPolicyService = mock(ChatModelPolicyService.class);
    private final ConversationStateService stateService = mock(ConversationStateService.class);
    private final ActiveAgentRunRegistry activeRunRegistry = mock(ActiveAgentRunRegistry.class);
    private final BusinessResponseAccessService responseAccessService = mock(BusinessResponseAccessService.class);
    private AiChatSessionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AiChatSessionServiceImpl(
                sessionMapper,
                messageMapper,
                objectMapper,
                modelPolicyService,
                stateService,
                activeRunRegistry,
                new ResponseChecksumService(objectMapper),
                responseAccessService
        );
        AiChatSession session = new AiChatSession();
        session.setId("conversation-1");
        session.setUserId("user-1");
        session.setDeleted(0);
        when(sessionMapper.selectOne(any())).thenReturn(session);
    }

    @Test
    void recoveryReturnsOnlyReauthorizedAndSanitizedSnapshot() {
        AiChatMessage message = assistantMessage("机密业务回答", storedJson());
        when(messageMapper.selectList(any())).thenReturn(List.of(message));
        when(responseAccessService.authorizeAndSanitize(
                storedJson(), "user-1", "conversation-1", "run-1", "Bearer current-token"
        )).thenReturn(externalJson());

        var snapshot = service.getResponseSnapshot(
                "user-1", "conversation-1", "run-1", "response-1", "Bearer current-token"
        );

        assertThat(snapshot.state()).isEqualTo("READY");
        assertThat(snapshot.documentJson()).isEqualTo(externalJson()).doesNotContain("_access");
    }

    @Test
    void revokedBusinessResponseCannotBeRecovered() {
        when(messageMapper.selectList(any())).thenReturn(List.of(
                assistantMessage("机密业务回答", storedJson())
        ));
        when(responseAccessService.authorizeAndSanitize(any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(404, "回答不存在或无权访问"));

        assertThatThrownBy(() -> service.getResponseSnapshot(
                "user-1", "conversation-1", "run-1", "response-1", "Bearer current-token"
        )).isInstanceOf(BusinessException.class)
                .hasMessage("回答不存在或无权访问");
    }

    @Test
    void revokedBusinessResponseIsHiddenFromHistoryAndMemory() {
        AiChatMessage user = new AiChatMessage();
        user.setRole("USER");
        user.setContent("查询项目");
        AiChatMessage assistant = assistantMessage("机密业务回答", storedJson());
        when(messageMapper.selectList(any())).thenReturn(new ArrayList<>(List.of(user, assistant)));
        when(responseAccessService.authorizeAndSanitize(any(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(404, "回答不存在或无权访问"));

        var history = service.listMessages(
                "user-1", "conversation-1", "Bearer current-token"
        );
        String memory = service.buildMemory(
                "user-1", "conversation-1", "Bearer current-token"
        );

        assertThat(history).hasSize(2);
        assertThat(history.get(1).getContent()).isEqualTo("该历史回答当前无权访问，请重新查询。");
        assertThat(history.get(1).getPayloadJson()).isNull();
        assertThat(memory).contains("用户：查询项目").doesNotContain("机密业务回答");
    }

    private AiChatMessage assistantMessage(String content, String payloadJson) {
        AiChatMessage message = new AiChatMessage();
        message.setId(1L);
        message.setRole("ASSISTANT");
        message.setContent(content);
        message.setMessageType("TEXT");
        message.setPayloadJson(payloadJson);
        message.setRunId("run-1");
        return message;
    }

    private String storedJson() {
        return externalJson().replace(
                "\"blocks\":[]",
                "\"_access\":{\"subjectType\":\"PROJECT\",\"subjectId\":\"raw-project-id\"},\"blocks\":[]"
        );
    }

    private String externalJson() {
        return """
                {"schemaVersion":2,"responseId":"response-1","runId":"run-1",
                "conversationId":"conversation-1","mode":"CHAT","status":"COMPLETED",
                "dataComplete":true,"context":{"subjectType":"PROJECT","subjectId":"P-1001"},
                "blocks":[],"references":[],"meta":{}}
                """.replace("\n", "");
    }
}
