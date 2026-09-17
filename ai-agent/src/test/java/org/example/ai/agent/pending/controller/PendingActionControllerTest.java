package org.example.ai.agent.pending.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.pending.entity.PendingAction;
import org.example.ai.agent.pending.service.PendingActionService;
import org.example.ai.agent.security.CurrentUserProvider;
import org.example.ai.agent.vo.PendingActionVO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 待确认操作接口安全返回测试。
 */
class PendingActionControllerTest {

    /** 状态接口必须脱敏嵌套认证字段并展示UNKNOWN提示。 */
    @Test
    void masksPreviewInputAndBuildsUnknownMarkdown() {
        PendingActionService pendingActionService = mock(PendingActionService.class);
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        PendingAction action = new PendingAction();
        action.setRunId("run-1");
        action.setCapabilityCode("project.update");
        action.setCapabilityName("更新项目");
        action.setInputJson("{\"projectId\":1,\"auth\":{\"accessToken\":\"secret-value\"}}");
        action.setStatus("UNKNOWN");
        action.setExpireAt(LocalDateTime.now().plusMinutes(10));
        action.setErrorMessage("业务系统执行结果暂时无法确认，请人工核实。");
        when(currentUserProvider.getRequiredUserId()).thenReturn("user-1");
        when(pendingActionService.getAction("run-1", "user-1")).thenReturn(action);
        PendingActionController controller = new PendingActionController(
                pendingActionService,
                new ObjectMapper(),
                currentUserProvider
        );

        Result<PendingActionVO> response = controller.detail("run-1");
        Map<?, ?> auth = (Map<?, ?>) response.getData().getInput().get("auth");

        assertThat(auth.get("accessToken")).isEqualTo("******");
        assertThat(response.getData().getMarkdown()).contains("操作结果待确认", "不要重复提交");
        assertThat(response.getData().getErrorMessage()).doesNotContain("secret-value");
    }
}
