package org.example.ai.agent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.chat.protocol.block.StatusListBlock;
import org.example.ai.agent.chat.protocol.response.AiResponse;
import org.example.ai.agent.chat.protocol.response.ResponseContext;
import org.example.ai.agent.chat.protocol.response.ResponseMeta;
import org.example.ai.agent.chat.stream.ResponseChecksumService;
import org.example.ai.agent.chat.stream.ResponseSequenceGenerator;
import org.example.ai.agent.chat.stream.ResponseStreamContext;
import org.example.ai.agent.chat.stream.ResponseStreamEventFactory;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.PresentationMode;
import org.example.ai.agent.common.enums.protocol.ResponseStatus;
import org.example.ai.agent.common.enums.protocol.Tone;
import org.example.ai.agent.plan.DynamicCapabilityPlan;
import org.example.ai.agent.router.IntentResult;
import org.example.ai.agent.router.RouteType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessAssistantStreamIntegrationTest {

    @Test
    void businessQueryStopSignalTracksCancellationAndConnectionClose() {
        var cancelled = newBusinessStream();
        assertThat(cancelled.shouldStopBusinessQuery()).isFalse();
        assertThat(cancelled.requestCancellation()).isTrue();
        assertThat(cancelled.shouldStopBusinessQuery()).isTrue();
        assertThatThrownBy(() -> cancelled.startTextResponse(
                "late", "迟到内容", 0, BlockSource.AI
        )).isInstanceOf(CancellationException.class);

        var disconnected = newBusinessStream();
        assertThat(disconnected.shouldStopBusinessQuery()).isFalse();
        disconnected.connectionClosed();
        assertThat(disconnected.shouldStopBusinessQuery()).isTrue();
        assertThat(disconnected.isCancellationRequested()).isFalse();
    }

    private org.example.ai.agent.chat.support.AgentStreamSession newBusinessStream() {
        return new org.example.ai.agent.chat.support.AgentStreamSession(
                org.mockito.Mockito.mock(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.class),
                "run-1", "conversation-1",
                org.mockito.Mockito.mock(org.example.ai.agent.observability.AgentMetrics.class),
                new ResponseChecksumService(new ObjectMapper().findAndRegisterModules()));
    }

    @Test
    void businessRouteRequiresConfirmedRegisteredReadCapability() throws Exception {
        Method predicate = Class.forName(
                "org.example.ai.agent.chat.service.impl.DefaultAgentOrchestrator"
        ).getDeclaredMethod("isConfirmedBusinessRead", IntentResult.class);
        predicate.setAccessible(true);
        DynamicCapabilityPlan read = new DynamicCapabilityPlan();
        read.setMatched(true);
        read.setSideEffect("READ");
        IntentResult valid = IntentResult.builder()
                .routeType(RouteType.BUSINESS_QUERY)
                .dynamicCapabilityPlan(read)
                .build();

        assertThat(predicate.invoke(null, valid)).isEqualTo(true);

        // 业务事实与制度证据的混合只读问题也必须进入同一业务助手主链。
        valid.setRouteType(RouteType.MIXED_QUERY);
        assertThat(predicate.invoke(null, valid)).isEqualTo(true);
        valid.setRouteType(RouteType.BUSINESS_QUERY);

        read.setSideEffect("WRITE");
        assertThat(predicate.invoke(null, valid)).isEqualTo(false);
        read.setSideEffect("READ");
        valid.setWorkflowPlan(org.mockito.Mockito.mock(
                org.example.ai.agent.workflow.plan.WorkflowPlan.class
        ));
        assertThat(predicate.invoke(null, valid)).isEqualTo(false);
    }

    @Test
    void contextSnapshotMustPrecedeStructuredStateAndChecksumsRemainValid() {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ResponseChecksumService checksumService = new ResponseChecksumService(objectMapper);
        ResponseStreamEventFactory factory = new ResponseStreamEventFactory(
                new ResponseStreamContext("response-1", "run-1", "conversation-1"),
                checksumService,
                new ResponseSequenceGenerator()
        );
        ResponseContext context = new ResponseContext(
                "PROJECT", "P-1001", "示例项目", "本年", null, null,
                LocalDateTime.of(2026, 9, 9, 10, 0)
        );
        AiResponse base = new AiResponse(
                AiResponse.CURRENT_SCHEMA_VERSION, "response-1", "run-1", "conversation-1",
                PresentationMode.CHAT, ResponseStatus.RUNNING, false, context,
                List.of(), List.of(), ResponseMeta.empty()
        );
        StatusListBlock status = new StatusListBlock(
                "dataset_status", "数据状态", 10, BlockStatus.READY, BlockSource.BUSINESS,
                List.of(new StatusListBlock.StatusItem(
                        "CONTRACT", "合同", "RUNNING", Tone.INFO, "正在查询"
                ))
        );

        var start = factory.responseStart(PresentationMode.CHAT, true);
        var baseSnapshot = factory.responseSnapshot(base);
        var blockStart = factory.blockStart(status);
        var blockDone = factory.blockDone(status);
        var done = factory.responseDone(base);

        assertThat(List.of(
                start.sequence(), baseSnapshot.sequence(), blockStart.sequence(),
                blockDone.sequence(), done.sequence()
        )).isSorted().doesNotHaveDuplicates();
        assertThat(baseSnapshot.sequence()).isLessThan(blockStart.sequence());
        assertThat(baseSnapshot.payload().checksum()).isEqualTo(
                checksumService.calculate(baseSnapshot.payload().documentJson())
        );
        assertThat(done.payload().checksum()).isEqualTo(
                checksumService.calculate(done.payload().documentJson())
        );
    }
}
