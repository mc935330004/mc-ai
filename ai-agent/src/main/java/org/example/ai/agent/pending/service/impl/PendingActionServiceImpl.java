package org.example.ai.agent.pending.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.config.PendingActionProperties;
import org.example.ai.agent.common.enums.PendingActionStatus;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.pending.entity.PendingAction;
import org.example.ai.agent.pending.mapper.PendingActionMapper;
import org.example.ai.agent.pending.service.PendingActionService;
import org.example.ai.agent.plan.DynamicCapabilityPlan;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.plan.StepType;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 待确认操作服务实现。
 */
@Service
@RequiredArgsConstructor
public class PendingActionServiceImpl extends ServiceImpl<PendingActionMapper, PendingAction>
        implements PendingActionService {
    private final BusinessCapabilityExecutor businessCapabilityExecutor;
    private final ObjectMapper objectMapper;
    private final PendingActionProperties properties;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PendingAction createPendingAction( String runId, String userId,DynamicCapabilityPlan plan ) {
        if (!StringUtils.hasText(runId)) {
            throw new BusinessException(400, "待确认操作缺少 runId");
        }
        // 写操作必须绑定真实用户，不能创建匿名操作
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException(400, "写操作缺少用户身份");
        }
        if (plan == null
                || !plan.isMatched()
                || !"WRITE".equalsIgnoreCase(plan.getSideEffect())) {
            throw new BusinessException(400, "当前计划不是有效的 WRITE 操作");
        }
        // 同一个 runId 重复生成预览时，直接返回原记录
        PendingAction existing = lambdaQuery().eq(PendingAction::getRunId, runId)
                .one();
        if (existing != null) {
            return existing;
        }
        PendingAction action = new PendingAction();
        action.setRunId(runId);
        action.setUserId(userId);
        action.setCapabilityCode(plan.getCapabilityCode());
        action.setCapabilityName(plan.getCapabilityName());
        action.setInputJson(toJson(plan.getInput()));
        action.setActionSummary("准备执行：" + plan.getCapabilityName());
        action.setStatus(PendingActionStatus.PENDING.getCode());
        action.setCreatedAt(LocalDateTime.now());
        action.setUpdatedAt(LocalDateTime.now());
        // 当前一个 runId 只允许对应一个写操作，因此直接作为幂等键
        action.setIdempotencyKey(runId);
        action.setExpireAt( LocalDateTime.now().plusMinutes(properties.getConfirmTimeoutMinutes()));
        save(action);
        return action;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PendingAction getAction(String runId, String userId) {
        PendingAction action = findOwnedAction(runId, userId);
        // 查询时发现操作过期，通过状态条件进行原子更新
        if (PendingActionStatus.PENDING.getCode().equals(action.getStatus()) && action.getExpireAt().isBefore(LocalDateTime.now())) {
            lambdaUpdate()
                    .eq(PendingAction::getId, action.getId())
                    .eq(PendingAction::getStatus, PendingActionStatus.PENDING.getCode())
                    .set(PendingAction::getStatus, PendingActionStatus.EXPIRED.getCode())
                    .update();

            // 重新查询，避免返回并发情况下的旧状态
            action = getById(action.getId());
        }

        return action;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PendingAction cancelAction(String runId, String userId) {
        // 先触发过期检查和用户归属校验
        PendingAction action = getAction(runId, userId);
        if (!PendingActionStatus.PENDING.getCode().equals(action.getStatus())) {
            throw new BusinessException( 400,"当前操作状态为 " + action.getStatus() + "，不能取消");
        }
        // 只有 PENDING 才能更新，避免与确认操作并发冲突
        boolean updated = lambdaUpdate()
                .eq(PendingAction::getId, action.getId())
                .eq(PendingAction::getUserId, userId)
                .eq(PendingAction::getStatus, PendingActionStatus.PENDING.getCode())
                .set(PendingAction::getStatus, PendingActionStatus.CANCELLED.getCode())
                .update();
        if (!updated) {
            throw new BusinessException(409, "操作状态已经发生变化，请刷新后重试");
        }
        return getById(action.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PendingAction confirmAction(String runId, String userId) {
        // 同时完成用户归属和过期状态检查
        PendingAction action = getAction(runId, userId);
        // 重复点击确认时直接返回，保证接口幂等
        if (PendingActionStatus.CONFIRMED.getCode().equals(action.getStatus())) {
            return action;
        }
        if (!PendingActionStatus.PENDING.getCode().equals(action.getStatus())) {
            throw new BusinessException(400,"当前操作状态为 " + action.getStatus() + "，不能确认");
        }
        LocalDateTime now = LocalDateTime.now();
        /*
         * 原子状态转换：
         * 1. 必须属于当前用户；
         * 2. 状态必须仍是 PENDING；
         * 3. 操作必须尚未过期。
         */
        boolean updated = lambdaUpdate()
                .eq(PendingAction::getId, action.getId())
                .eq(PendingAction::getUserId, userId)
                .eq(PendingAction::getStatus, PendingActionStatus.PENDING.getCode())
                .gt(PendingAction::getExpireAt, now)
                .set(PendingAction::getStatus, PendingActionStatus.CONFIRMED.getCode())
                .set(PendingAction::getConfirmedAt, now)
                .update();
        if (!updated) {
            // 重新读取，判断是重复确认、过期还是被取消
            PendingAction latest = getAction(runId, userId);
            if (PendingActionStatus.CONFIRMED.getCode().equals(latest.getStatus())) {
                return latest;
            }
            throw new BusinessException( 409,"操作状态已经变为 " + latest.getStatus() + "，确认失败");
        }
        return getById(action.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PendingAction executeConfirmedAction(String runId, String userId,String authorization) {
        PendingAction action = getAction(runId, userId);

        // 已成功时直接返回第一次执行结果，禁止重复调用业务系统
        if (PendingActionStatus.SUCCESS.getCode().equals(action.getStatus())) {
            return action;
        }

        if (!PendingActionStatus.CONFIRMED.getCode().equals(action.getStatus())) {
            throw new BusinessException(400,"当前操作状态为 " + action.getStatus() + "，不能执行");
        }
        // 原子抢占执行权，防止两个请求同时调用业务系统
        boolean claimed = lambdaUpdate()
                .eq(PendingAction::getId, action.getId())
                .eq(PendingAction::getStatus, PendingActionStatus.CONFIRMED.getCode())
                .set(PendingAction::getStatus, PendingActionStatus.EXECUTING.getCode())
                .update();

        if (!claimed) {
            throw new BusinessException(409, "操作正在执行或已经执行");
        }
        try {
            Map<String, Object> input = objectMapper.readValue(
                    action.getInputJson(),
                    new TypeReference<>() {}
            );

            PlanStep step = PlanStep.builder()
                    .stepNo(1)
                    .stepType(StepType.BUSINESS_TOOL)
                    .stepName("执行已确认操作：" + action.getCapabilityName())
                    .capabilityCode(action.getCapabilityCode())
                    .input(input)
                    .outputKey("actionResult")
                    .build();

            ToolExecutionContext context = ToolExecutionContext.builder()
                    .runId(runId)
                    .userId(userId)
                    .variables(new java.util.LinkedHashMap<>())
                    .authorization(authorization)
                    .build();

            ToolResult result = businessCapabilityExecutor.executeConfirmedWrite(context, step, action.getIdempotencyKey());
            LocalDateTime now = LocalDateTime.now();
            if (result.isSuccess()) {
                lambdaUpdate()
                        .eq(PendingAction::getId, action.getId())
                        .eq(PendingAction::getStatus, PendingActionStatus.EXECUTING.getCode())
                        .set(PendingAction::getStatus, PendingActionStatus.SUCCESS.getCode())
                        .set(PendingAction::getExecutedAt, now)
                        .set(PendingAction::getOutputJson, toJson(result.getData()))
                        .update();
            } else {
                lambdaUpdate()
                        .eq(PendingAction::getId, action.getId())
                        .eq(PendingAction::getStatus, PendingActionStatus.EXECUTING.getCode())
                        .set(PendingAction::getStatus, PendingActionStatus.FAILED.getCode())
                        .set(PendingAction::getExecutedAt, now)
                        .set(PendingAction::getErrorMessage, result.getErrorMessage())
                        .update();
            }

            return getById(action.getId());
        } catch (Exception e) {
            lambdaUpdate()
                    .eq(PendingAction::getId, action.getId())
                    .eq(PendingAction::getStatus, PendingActionStatus.EXECUTING.getCode())
                    .set(PendingAction::getStatus, PendingActionStatus.FAILED.getCode())
                    .set(PendingAction::getExecutedAt, LocalDateTime.now())
                    .set(PendingAction::getErrorMessage, e.getMessage())
                    .update();

            return getById(action.getId());
        }
    }

    @Override
    public PendingAction confirmAndExecuteAction(String runId, String userId,String authorization) {
        PendingAction action = getAction(runId, userId);
        // 已经成功时直接返回历史结果，禁止重复调用业务系统
        if (PendingActionStatus.SUCCESS.getCode().equals(action.getStatus())) {
            return action;
        }
        // PENDING 状态先完成用户确认
        if (PendingActionStatus.PENDING.getCode().equals(action.getStatus())) {
            action = confirmAction(runId, userId);
        }
        // 只有 CONFIRMED 才能进入真实执行
        if (PendingActionStatus.CONFIRMED.getCode().equals(action.getStatus())) {
            return executeConfirmedAction(runId, userId,authorization);
        }
        throw new BusinessException(400,"当前操作状态为 " + action.getStatus() + "，不能确认执行");
    }

    /**
     * 将确认后需要使用的固定参数序列化为 JSON。
     */
    private String toJson(Object input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new BusinessException(400, "写操作参数序列化失败");
        }
    }
    /**
     * 根据 runId 查询操作，并校验操作是否属于当前用户。
     */
    private PendingAction findOwnedAction(String runId, String userId) {
        if (!StringUtils.hasText(runId) || !StringUtils.hasText(userId)) {
            throw new BusinessException(400, "runId 和 userId 不能为空");
        }
        PendingAction action = lambdaQuery()
                .eq(PendingAction::getRunId, runId)
                .one();
        if (action == null) {
            throw new BusinessException(404, "待确认操作不存在");
        }
        if (!userId.equals(action.getUserId())) {
            throw new BusinessException(403, "无权访问其他用户的操作");
        }
        return action;
    }
}