package org.example.ai.agent.chat.memory.service;

import org.example.ai.agent.chat.entity.AgentRequest;
import org.example.ai.agent.chat.memory.model.BusinessConversationState;
import org.example.ai.agent.common.enums.SnapshotReadMode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationContextResolverTest {

    /**
     * 普通问题默认只复用仍然新鲜的安全快照。
     */
    @Test
    void ordinaryQuestionUsesFreshSnapshotMode() {
        AgentRequest request = request("继续分析这个项目");

        resolverWithoutState().resolve(request, "run-1");

        assertThat(request.getSnapshotReadMode()).isEqualTo(SnapshotReadMode.REUSE_IF_FRESH);
    }

    /**
     * 明确引用刚才结果时允许读取仍在保留期内的历史快照。
     */
    @Test
    void explicitPreviousResultUsesSnapshotMode() {
        AgentRequest request = request("分析刚才的结果");

        resolverWithoutState().resolve(request, "run-1");

        assertThat(request.getSnapshotReadMode()).isEqualTo(SnapshotReadMode.REUSE_SNAPSHOT);
    }

    /**
     * 用户要求最新数据时必须绕过全部旧快照。
     */
    @Test
    void latestDataQuestionForcesLiveMode() {
        AgentRequest request = request("按最新数据重新查询");

        resolverWithoutState().resolve(request, "run-1");

        assertThat(request.getSnapshotReadMode()).isEqualTo(SnapshotReadMode.FORCE_LIVE);
    }

    /**
     * 同时包含历史指代和最新要求时，以强制实时查询为准。
     */
    @Test
    void latestMarkerOverridesPreviousResultMarker() {
        AgentRequest request = request("按最新数据重新分析刚才的结果");

        resolverWithoutState().resolve(request, "run-1");

        assertThat(request.getSnapshotReadMode()).isEqualTo(SnapshotReadMode.FORCE_LIVE);
    }

    /**
     * 导出上一轮结果时必须继承服务端保存的来源运行，不能改成当前导出运行。
     */
    @Test
    void previousResultExportInheritsServerSourceRun() {
        ConversationContextRewriteService rewriteService = mock(ConversationContextRewriteService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        BusinessConversationState state = new BusinessConversationState();
        state.setLastRunId("run-previous");
        state.setLastInput(Map.of("selectionToken", "selection-token"));
        state.setResultArtifactId("artifact-previous");
        when(stateService.loadState("user-1", "conversation-1")).thenReturn(Optional.of(state));
        AgentRequest request = request("导出刚才的结果为 PDF");

        String rewritten = new ConversationContextResolver(rewriteService, stateService).resolve(request, "run-current");

        assertThat(rewritten).isEqualTo("导出刚才的结果为 PDF");
        assertThat(request.getSnapshotReadMode()).isEqualTo(SnapshotReadMode.REUSE_SNAPSHOT);
        assertThat(request.isResultAnalysisRequest()).isTrue();
        assertThat(request.getInheritedInput())
                .containsEntry("selectionToken", "selection-token")
                .containsEntry("sourceRunId", "run-previous");
    }

    private ConversationContextResolver resolverWithoutState() {
        ConversationContextRewriteService rewriteService = mock(ConversationContextRewriteService.class);
        ConversationStateService stateService = mock(ConversationStateService.class);
        when(stateService.loadState("user-1", "conversation-1")).thenReturn(Optional.empty());
        return new ConversationContextResolver(rewriteService, stateService);
    }

    private AgentRequest request(String question) {
        AgentRequest request = new AgentRequest();
        request.setUserId("user-1");
        request.setConversationId("conversation-1");
        request.setUserQuestion(question);
        return request;
    }
}
