package org.example.ai.agent.chat.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.common.enums.protocol.ResponseStreamEventType;
import org.example.ai.agent.vo.ActionFormVO;
import org.example.ai.agent.vo.ActionPreviewVO;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseStreamEventFactoryTest {

    @Test
    void actionFormUsesUnifiedEnvelope() {
        ResponseStreamEventFactory factory = factory();
        ActionFormVO form = ActionFormVO.builder()
                .workflowCode("project-write")
                .capabilityCode("project.update")
                .capabilityName("修改项目")
                .schema("{\"type\":\"object\"}")
                .initialValue(Map.of("projectId", "P-1"))
                .build();

        var event = factory.actionForm(form);

        // 写操作表单必须复用统一事件标识和全局序号。
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.eventId()).isEqualTo("response-1:1");
        assertThat(event.responseId()).isEqualTo("response-1");
        assertThat(event.runId()).isEqualTo("run-1");
        assertThat(event.conversationId()).isEqualTo("conversation-1");
        assertThat(event.sequence()).isEqualTo(1);
        assertThat(event.eventType()).isEqualTo(ResponseStreamEventType.ACTION_FORM);
        assertThat(event.payload()).isSameAs(form);
    }

    @Test
    void actionPreviewContinuesSameSequence() {
        ResponseStreamEventFactory factory = factory();
        factory.actionForm(ActionFormVO.builder().capabilityCode("project.update").build());
        ActionPreviewVO preview = ActionPreviewVO.builder()
                .runId("run-1")
                .capabilityCode("project.update")
                .capabilityName("修改项目")
                .input(Map.of("projectId", "P-1"))
                .displayInput(Map.of("项目编号", "P-1"))
                .status("PENDING")
                .requireConfirm(true)
                .build();

        var event = factory.actionPreview(preview);

        // 表单和预览不能各自重新生成事件序号。
        assertThat(event.eventId()).isEqualTo("response-1:2");
        assertThat(event.sequence()).isEqualTo(2);
        assertThat(event.eventType()).isEqualTo(ResponseStreamEventType.ACTION_PREVIEW);
        assertThat(event.payload()).isSameAs(preview);
    }

    @Test
    void workflowResultUsesUnifiedEnvelope() {
        ResponseStreamEventFactory factory = factory();
        Map<String, Object> result = Map.of(
                "workflowCode", "project-query",
                "success", true
        );

        var event = factory.workflowResult("工作流执行完成。", result);

        // 工作流结果只发送安全摘要，不能退回旧事件结构。
        assertThat(event.eventType()).isEqualTo(ResponseStreamEventType.WORKFLOW_RESULT);
        assertThat(event.payload().content()).isEqualTo("工作流执行完成。");
        assertThat(event.payload().data()).isEqualTo(result);
    }

    @Test
    void reportFollowUpUsesUnifiedEnvelope() {
        ResponseStreamEventFactory factory = factory();

        var event = factory.reportFollowUp("请选择需要继续分析的项目。");

        // 报告追问仍作为独立消息发送，但必须使用统一事件序号。
        assertThat(event.eventType()).isEqualTo(ResponseStreamEventType.REPORT_FOLLOW_UP);
        assertThat(event.eventId()).isEqualTo("response-1:1");
        assertThat(event.payload().content()).isEqualTo("请选择需要继续分析的项目。");
        assertThat(event.payload().data()).containsEntry("prompt", "请选择需要继续分析的项目。");
    }

    private ResponseStreamEventFactory factory() {
        return new ResponseStreamEventFactory(
                new ResponseStreamContext("response-1", "run-1", "conversation-1"),
                new ResponseChecksumService(new ObjectMapper()),
                new ResponseSequenceGenerator()
        );
    }
}
