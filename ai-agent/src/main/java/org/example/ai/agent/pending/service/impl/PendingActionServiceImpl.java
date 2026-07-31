package org.example.ai.agent.pending.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.config.PendingActionProperties;
import org.example.ai.agent.common.enums.PendingActionStatus;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.pending.audit.ActionAuditRecorder;
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
    private final ActionAuditRecorder actionAuditRecorder;
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
        action.setActionSummary(buildActionSummary(
                        plan.getCapabilityName(),
                        plan.getDisplayInput()));
        action.setStatus(PendingActionStatus.PENDING.getCode());
        action.setCreatedAt(LocalDateTime.now());
        action.setUpdatedAt(LocalDateTime.now());
        // 当前一个 runId 只允许对应一个写操作，因此直接作为幂等键
        action.setIdempotencyKey(runId);
        action.setExpireAt( LocalDateTime.now().plusMinutes(properties.getConfirmTimeoutMinutes()));
        save(action);
        // 记录WRITE操作预览创建事件。
        actionAuditRecorder.record(action,ActionAuditRecorder.PREVIEW_CREATED,null);
        return action;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PendingAction getAction(String runId, String userId) {
        PendingAction action = findOwnedAction(runId, userId);
        // 查询时发现操作过期，通过状态条件进行原子更新
        if (PendingActionStatus.PENDING.getCode().equals(action.getStatus()) && action.getExpireAt().isBefore(LocalDateTime.now())) {
            boolean expired = lambdaUpdate()
                    .eq(PendingAction::getId, action.getId())
                    .eq(PendingAction::getStatus, PendingActionStatus.PENDING.getCode())
                    .set(PendingAction::getStatus, PendingActionStatus.EXPIRED.getCode())
                    .update();
            action = getById(action.getId());
            if (expired) {
                // 只有真正完成状态转换时才记录，避免重复查询产生重复审计。
                actionAuditRecorder.record( action,ActionAuditRecorder.EXPIRED,null);
            }
        }

        return action;
    }

    /**
     * 取消尚未执行的待确认操作。
     *
     * 状态处理规则：
     * 1. PENDING：正常取消并记录审计日志；
     * 2. CANCELLED：重复取消，直接返回当前记录；
     * 3. EXPIRED：操作已经安全过期，直接返回，由前端恢复原表单；
     * 4. 其他状态：禁止取消。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PendingAction cancelAction(
            String runId,
            String userId) {

        /*
         * getAction会完成：
         * 1. 当前用户归属校验；
         * 2. PENDING操作的过期检查；
         * 3. 已过期操作自动转换为EXPIRED。
         */
        PendingAction action = getAction(runId, userId);

        /*
         * CANCELLED属于幂等取消。
         * 用户重复点击时不需要再次更新数据库。
         */
        if (PendingActionStatus.CANCELLED
                .getCode()
                .equals(action.getStatus())) {
            return action;
        }

        /*
         * EXPIRED表示该操作已经失效，不可能再写入业务系统。
         *
         * 这里保持EXPIRED审计状态，不强行改成CANCELLED，
         * 但不再向前端抛出“不能取消”的异常。
         * 前端收到EXPIRED后会恢复原始动态表单。
         */
        if (PendingActionStatus.EXPIRED
                .getCode()
                .equals(action.getStatus())) {
            return action;
        }

        /*
         * CONFIRMED、EXECUTING、SUCCESS、FAILED等状态
         * 不能通过取消按钮重新编辑。
         */
        if (!PendingActionStatus.PENDING
                .getCode()
                .equals(action.getStatus())) {
            throw new BusinessException(
                    400,
                    "当前操作状态为 "
                            + action.getStatus()
                            + "，不能取消"
            );
        }

        /*
         * 使用状态条件完成原子更新，
         * 防止取消和确认请求同时执行。
         */
        boolean updated = lambdaUpdate()
                .eq(PendingAction::getId, action.getId())
                .eq(PendingAction::getUserId, userId)
                .eq(PendingAction::getStatus,PendingActionStatus.PENDING.getCode())
                .set(PendingAction::getStatus,PendingActionStatus.CANCELLED.getCode())
                .set(
                        PendingAction::getUpdatedAt,
                        LocalDateTime.now()
                )
                .update();

        /*
         * 更新失败可能是并发请求已经改变了状态。
         * 对CANCELLED和EXPIRED继续保持幂等返回，
         * 其他状态提示用户刷新。
         */
        if (!updated) {
            PendingAction latest = getAction(runId, userId);

            if (PendingActionStatus.CANCELLED
                    .getCode()
                    .equals(latest.getStatus())
                    || PendingActionStatus.EXPIRED
                    .getCode()
                    .equals(latest.getStatus())) {
                return latest;
            }

            throw new BusinessException(
                    409,
                    "操作状态已经发生变化，请刷新后重试"
            );
        }

        PendingAction cancelledAction =
                getById(action.getId());

        /*
         * 只有真正从PENDING转换为CANCELLED时，
         * 才记录一次取消审计。
         */
        actionAuditRecorder.record(
                cancelledAction,
                ActionAuditRecorder.CANCELLED,
                null
        );

        return cancelledAction;
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
        // 只有成功抢占执行权的请求才记录执行开始。
        actionAuditRecorder.record(action,ActionAuditRecorder.EXECUTION_STARTED,null);
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
            PendingAction finishedAction = getById(action.getId());

            actionAuditRecorder.record(finishedAction, result.isSuccess()
                            ? ActionAuditRecorder.EXECUTION_SUCCEEDED
                            : ActionAuditRecorder.EXECUTION_FAILED,
                    result.isSuccess()? null: result.getErrorCode());
            return finishedAction;
        } catch (Exception e) {
            lambdaUpdate()
                    .eq(PendingAction::getId, action.getId())
                    .eq(PendingAction::getStatus, PendingActionStatus.EXECUTING.getCode())
                    .set(PendingAction::getStatus, PendingActionStatus.FAILED.getCode())
                    .set(PendingAction::getExecutedAt, LocalDateTime.now())
                    .set(PendingAction::getErrorMessage, e.getMessage())
                    .update();

            PendingAction failedAction = getById(action.getId());
            // 不把原始异常写入追加式审计，避免敏感信息泄露。
            actionAuditRecorder.record(failedAction, ActionAuditRecorder.EXECUTION_FAILED,"UNEXPECTED_EXCEPTION");

            return failedAction;
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
    /**
     * 构建中文操作预览摘要。
     *
     * inputJson仍然保存真实ID；
     * 中文名称只进入展示摘要。
     */
    private String buildActionSummary(
            String capabilityName,
            Map<String, Object> displayInput) {

        String base =
                "准备执行：" + capabilityName;

        if (displayInput == null
                || displayInput.isEmpty()) {
            return base;
        }

        String parameters =
                displayInput.entrySet()
                        .stream()
                        /*
                         * 安全字段不能进入数据库摘要。
                         */
                        .filter(entry ->
                                !isSensitiveName(
                                        entry.getKey()
                                )
                        )
                        .map(entry ->
                                entry.getKey()
                                        + "="
                                        + safeSummaryValue(
                                        entry.getValue()
                                )
                        )
                        .reduce(
                                (left, right) ->
                                        left + "，" + right
                        )
                        .orElse("");

        return StringUtils.hasText(parameters)
                ? base + "；参数：" + parameters
                : base;
    }

    private boolean isSensitiveName(String fieldName) {
        if (!StringUtils.hasText(fieldName)) {
            return false;
        }

        String normalized =
                fieldName.toLowerCase();

        return normalized.contains("token")
                || normalized.contains("authorization")
                || normalized.contains("cookie")
                || normalized.contains("password");
    }

    private String safeSummaryValue(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value)
                .replace("\r", " ")
                .replace("\n", " ");
    }
}