package org.example.ai.agent.pending.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.access.service.AgentResourceAccessService;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.common.config.PendingActionProperties;
import org.example.ai.agent.common.enums.PendingActionStatus;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.pending.audit.ActionAuditRecorder;
import org.example.ai.agent.pending.entity.PendingAction;
import org.example.ai.agent.pending.mapper.PendingActionMapper;
import org.example.ai.agent.pending.service.impl.PendingActionServiceImpl;
import org.example.ai.agent.plan.DynamicCapabilityPlan;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.ToolResult;
import org.example.ai.agent.trace.mapper.RunTraceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WRITE待确认操作状态机测试。
 */
@ExtendWith(MockitoExtension.class)
class PendingActionServiceTest {

    @Mock
    private PendingActionMapper pendingActionMapper;
    @Mock
    private BusinessCapabilityExecutor businessCapabilityExecutor;
    @Mock
    private ActionAuditRecorder actionAuditRecorder;
    @Mock
    private AgentResourceAccessService resourceAccessService;
    @Mock
    private CapabilityDefinitionService capabilityDefinitionService;
    @Mock
    private RunTraceMapper runTraceMapper;

    private PendingActionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PendingActionServiceImpl(
                businessCapabilityExecutor,
                new ObjectMapper(),
                new PendingActionProperties(),
                actionAuditRecorder,
                resourceAccessService,
                capabilityDefinitionService,
                runTraceMapper
        );
        // 中文注释：ServiceImpl的Mapper由Spring注入，单元测试中手动绑定Mock。
        ReflectionTestUtils.setField(service, "baseMapper", pendingActionMapper);
    }

    /** 重复确认必须复用第一次结果，只允许一次外部写调用。 */
    @Test
    void repeatedConfirmationExecutesExternalWriteOnce() {
        PendingAction action = pendingAction(PendingActionStatus.PENDING, "user-1");
        bindState(action, List.of("CONFIRMED", "EXECUTING", "SUCCESS"));
        allowFrozenAction(action);
        when(businessCapabilityExecutor.executeConfirmedWrite(any(), any(), any()))
                .thenReturn(ToolResult.builder().success(true).data(Map.of("result", "ok")).build());

        PendingAction first = service.confirmAndExecuteAction("run-1", "user-1", "Bearer token");
        PendingAction second = service.confirmAndExecuteAction("run-1", "user-1", "Bearer token");

        assertThat(first.getStatus()).isEqualTo("SUCCESS");
        assertThat(second.getStatus()).isEqualTo("SUCCESS");
        verify(businessCapabilityExecutor, times(1)).executeConfirmedWrite(any(), any(), any());
    }

    /** 外部超时进入UNKNOWN，重复请求不能再次调用WRITE接口。 */
    @Test
    void timeoutBecomesUnknownAndIsNotRetried() {
        PendingAction action = pendingAction(PendingActionStatus.CONFIRMED, "user-1");
        bindState(action, List.of("EXECUTING", "UNKNOWN"));
        allowFrozenAction(action);
        when(businessCapabilityExecutor.executeConfirmedWrite(any(), any(), any()))
                .thenReturn(ToolResult.builder().success(false)
                        .errorCode("BUSINESS_API_TIMEOUT_OR_NETWORK_ERROR").build());

        PendingAction first = service.executeConfirmedAction("run-1", "user-1", "Bearer token");
        PendingAction second = service.executeConfirmedAction("run-1", "user-1", "Bearer token");

        assertThat(first.getStatus()).isEqualTo("UNKNOWN");
        assertThat(second.getStatus()).isEqualTo("UNKNOWN");
        verify(businessCapabilityExecutor, times(1)).executeConfirmedWrite(any(), any(), any());
    }

    /** 能力发布版本变化后拒绝执行。 */
    @Test
    void changedCapabilityVersionIsRejected() {
        PendingAction action = pendingAction(PendingActionStatus.PENDING, "user-1");
        bindState(action, List.of("REJECTED"));
        when(runTraceMapper.selectCount(any())).thenReturn(1L);
        when(capabilityDefinitionService.getEnabledByCode("project.update"))
                .thenReturn(writeCapability(2L, "checksum-2"));

        PendingAction result = service.confirmAction("run-1", "user-1");

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        verify(businessCapabilityExecutor, never()).executeConfirmedWrite(any(), any(), any());
    }

    /** 权限撤销后拒绝执行。 */
    @Test
    void revokedPermissionIsRejected() {
        PendingAction action = pendingAction(PendingActionStatus.PENDING, "user-1");
        bindState(action, List.of("REJECTED"));
        allowFrozenAction(action);
        org.mockito.Mockito.doThrow(new BusinessException(403, "无权执行"))
                .when(resourceAccessService).requireCapabilityAccess("project.update", "user-1");

        PendingAction result = service.confirmAction("run-1", "user-1");

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        verify(businessCapabilityExecutor, never()).executeConfirmedWrite(any(), any(), any());
    }

    /** 参数正文与冻结摘要不一致时拒绝执行。 */
    @Test
    void changedInputDigestIsRejected() {
        PendingAction action = pendingAction(PendingActionStatus.PENDING, "user-1");
        action.setInputJson("{\"projectId\":2}");
        bindState(action, List.of("REJECTED"));

        PendingAction result = service.confirmAction("run-1", "user-1");

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        verify(businessCapabilityExecutor, never()).executeConfirmedWrite(any(), any(), any());
    }

    /** 过期操作保持EXPIRED且不会进入执行器。 */
    @Test
    void expiredActionCannotExecute() {
        PendingAction action = pendingAction(PendingActionStatus.PENDING, "user-1");
        action.setExpireAt(LocalDateTime.now().minusMinutes(1));
        bindState(action, List.of("EXPIRED"));

        PendingAction result = service.confirmAndExecuteAction("run-1", "user-1", "Bearer token");

        assertThat(result.getStatus()).isEqualTo("EXPIRED");
        verify(businessCapabilityExecutor, never()).executeConfirmedWrite(any(), any(), any());
    }

    /** 其他用户不能读取或确认待执行操作。 */
    @Test
    void crossUserConfirmationIsDenied() {
        PendingAction action = pendingAction(PendingActionStatus.PENDING, "owner");
        when(pendingActionMapper.selectOne(any())).thenReturn(action);

        assertThatThrownBy(() -> service.confirmAction("run-1", "other-user"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权访问其他用户的操作");
        verify(businessCapabilityExecutor, never()).executeConfirmedWrite(any(), any(), any());
    }

    /** DANGEROUS能力不能生成PendingAction。 */
    @Test
    void dangerousCapabilityDoesNotCreatePendingAction() {
        DynamicCapabilityPlan plan = new DynamicCapabilityPlan();
        plan.setMatched(true);
        plan.setCapabilityCode("project.delete");
        plan.setInput(Map.of("projectId", 1));
        when(pendingActionMapper.selectOne(any())).thenReturn(null);
        CapabilityDefinition dangerous = writeCapability(1L, "checksum-1");
        dangerous.setCapabilityCode("project.delete");
        dangerous.setSideEffect("DANGEROUS");
        when(capabilityDefinitionService.getEnabledByCode("project.delete")).thenReturn(dangerous);

        assertThatThrownBy(() -> service.createPendingAction("run-1", "user-1", plan))
                .isInstanceOf(BusinessException.class)
                .hasMessage("危险能力禁止生成待执行操作");

        verify(actionAuditRecorder).recordRejected(
                "run-1", "user-1", "project.delete", "更新项目", "DANGEROUS_CAPABILITY");
    }

    /** 绑定一条内存状态记录，并按顺序模拟条件更新结果。 */
    private void bindState(PendingAction action, List<String> updatedStatuses) {
        when(pendingActionMapper.selectOne(any())).thenReturn(action);
        when(pendingActionMapper.selectById(any())).thenReturn(action);
        AtomicInteger updateIndex = new AtomicInteger();
        when(pendingActionMapper.update(any(), any())).thenAnswer(invocation -> {
            int index = updateIndex.getAndIncrement();
            if (index >= updatedStatuses.size()) {
                return 0;
            }
            action.setStatus(updatedStatuses.get(index));
            return 1;
        });
    }

    /** 配置通过确认复验所需的当前能力和运行归属。 */
    private void allowFrozenAction(PendingAction action) {
        when(runTraceMapper.selectCount(any())).thenReturn(1L);
        when(capabilityDefinitionService.getEnabledByCode(action.getCapabilityCode()))
                .thenReturn(writeCapability(action.getCapabilityVersionId(), action.getCapabilityConfigChecksum()));
    }

    /** 创建一条具有有效冻结信息的测试操作。 */
    private PendingAction pendingAction(PendingActionStatus status, String userId) {
        String inputJson = "{\"projectId\":1}";
        PendingAction action = new PendingAction();
        action.setId(1L);
        action.setRunId("run-1");
        action.setUserId(userId);
        action.setCapabilityCode("project.update");
        action.setCapabilityName("更新项目");
        action.setCapabilityVersionId(1L);
        action.setCapabilityConfigChecksum("checksum-1");
        action.setInputJson(inputJson);
        action.setInputDigest(sha256(inputJson));
        action.setIdempotencyKey("run-1");
        action.setStatus(status.getCode());
        action.setExpireAt(LocalDateTime.now().plusMinutes(10));
        return action;
    }

    /** 创建当前正式发布的WRITE能力快照。 */
    private CapabilityDefinition writeCapability(Long versionId, String checksum) {
        CapabilityDefinition capability = new CapabilityDefinition();
        capability.setCapabilityCode("project.update");
        capability.setCapabilityName("更新项目");
        capability.setSideEffect("WRITE");
        capability.setActiveVersionId(versionId);
        capability.setConfigChecksum(checksum);
        return capability;
    }

    /** 使用与生产代码相同的JDK SHA-256算法生成测试摘要。 */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
