package org.example.ai.agent.pending.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.access.service.AgentResourceAccessService;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.ai.agent.trace.entity.RunTrace;
import org.example.ai.agent.trace.mapper.RunTraceMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

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
    private final AgentResourceAccessService resourceAccessService;
    private final CapabilityDefinitionService capabilityDefinitionService;
    private final RunTraceMapper runTraceMapper;
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PendingAction createPendingAction(String runId, String userId, DynamicCapabilityPlan plan) {
        if (!StringUtils.hasText(runId)) {
            throw new BusinessException(400, "待确认操作缺少runId");
        }
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException(400, "写操作缺少用户身份");
        }
        if (plan == null || !plan.isMatched() || plan.isNeedClarify()
                || !StringUtils.hasText(plan.getCapabilityCode())) {
            throw new BusinessException(400, "当前计划不是可执行的写操作");
        }
        String inputJson = toJson(plan.getInput());
        String inputDigest = sha256(inputJson);
        /*
         * 同一个runId只能绑定同一用户、同一能力和同一份固定参数。
         * 完全一致时复用原预览，禁止生成第二个幂等键。
         */
        PendingAction existing = lambdaQuery().eq(PendingAction::getRunId, runId).one();
        if (existing != null) {
            boolean sameAction = userId.equals(existing.getUserId())
                    && plan.getCapabilityCode().equals(existing.getCapabilityCode())
                    && inputDigest.equals(existing.getInputDigest());
            if (sameAction) {
                return existing;
            }
            throw new BusinessException(409, "当前runId已经绑定其他写操作");
        }

        /*
         * 安全属性只信任当前正式发布的能力快照。
         * 不使用模型或前端传入的能力名称、副作用级别和版本。
         */
        CapabilityDefinition capability = capabilityDefinitionService.getEnabledByCode(plan.getCapabilityCode());
        if (capability == null) {
            throw new BusinessException(404, "写操作能力不存在、未启用或未发布");
        }
        if ("DANGEROUS".equalsIgnoreCase(capability.getSideEffect())) {
            actionAuditRecorder.recordRejected(runId, userId, capability.getCapabilityCode(),
                    capability.getCapabilityName(), "DANGEROUS_CAPABILITY");
            throw new BusinessException(403, "危险能力禁止生成待执行操作");
        }
        if (!"WRITE".equalsIgnoreCase(capability.getSideEffect())) {
            actionAuditRecorder.recordRejected(runId, userId, capability.getCapabilityCode(),
                    capability.getCapabilityName(), "NOT_WRITE_CAPABILITY");
            throw new BusinessException(400, "当前能力不是WRITE操作");
        }
        if (capability.getActiveVersionId() == null || !StringUtils.hasText(capability.getConfigChecksum())) {
            throw new BusinessException(409, "写操作能力缺少有效发布版本");
        }

        LocalDateTime now = LocalDateTime.now();
        PendingAction action = new PendingAction();
        action.setRunId(runId);
        action.setUserId(userId);
        action.setCapabilityCode(capability.getCapabilityCode());
        action.setCapabilityName(capability.getCapabilityName());
        action.setCapabilityVersionId(capability.getActiveVersionId());
        action.setCapabilityConfigChecksum(capability.getConfigChecksum());
        action.setInputJson(inputJson);
        action.setInputDigest(inputDigest);
        action.setActionSummary(buildActionSummary(capability.getCapabilityName(), plan.getDisplayInput()));
        action.setStatus(PendingActionStatus.PENDING.getCode());
        action.setIdempotencyKey(runId);
        action.setExpireAt(now.plusMinutes(properties.getConfirmTimeoutMinutes()));
        action.setCreatedAt(now);
        action.setUpdatedAt(now);
        save(action);

        // 中文注释：预览创建后只记录关联信息，不记录完整操作参数。
        actionAuditRecorder.record(action, ActionAuditRecorder.PREVIEW_CREATED,
                "versionId=" + capability.getActiveVersionId());
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
        PendingAction action = getAction(runId, userId);
        /*
         * 重复确认直接返回当前结果。
         * EXECUTING和所有终态都不能再次进入确认流程。
         */
        if (isReusableResult(action.getStatus())) {
            return action;
        }
        if (!PendingActionStatus.PENDING.getCode().equals(action.getStatus())) {
            throw new BusinessException(400, "当前操作状态为" + action.getStatus() + "，不能确认");
        }
        String rejectionCode = validateFrozenAction(action, userId);
        if (rejectionCode != null) {
            return rejectAction(action, PendingActionStatus.PENDING.getCode(), rejectionCode);
        }
        LocalDateTime now = LocalDateTime.now();
        boolean updated = lambdaUpdate()
                .eq(PendingAction::getId, action.getId())
                .eq(PendingAction::getUserId, userId)
                .eq(PendingAction::getStatus, PendingActionStatus.PENDING.getCode())
                .gt(PendingAction::getExpireAt, now)
                .set(PendingAction::getStatus, PendingActionStatus.CONFIRMED.getCode())
                .set(PendingAction::getConfirmedAt, now)
                .set(PendingAction::getUpdatedAt, now)
                .update();
        if (!updated) {
            PendingAction latest = getAction(runId, userId);
            if (isReusableResult(latest.getStatus())) {
                return latest;
            }
            throw new BusinessException(409, "操作状态已经发生变化，请刷新后重试");
        }

        PendingAction confirmedAction = getById(action.getId());
        // 中文注释：只有真正完成状态转换时才记录一次确认审计。
        actionAuditRecorder.record(confirmedAction, ActionAuditRecorder.CONFIRMED, null);
        return confirmedAction;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PendingAction executeConfirmedAction(String runId, String userId, String authorization) {
        PendingAction action = getAction(runId, userId);

        /*
         * 正在执行或已经产生结果时直接返回。
         * 特别是UNKNOWN不能再次调用外部WRITE接口。
         */
        if (isReusableResult(action.getStatus())
                && !PendingActionStatus.CONFIRMED.getCode().equals(action.getStatus())) {
            return action;
        }
        if (!PendingActionStatus.CONFIRMED.getCode().equals(action.getStatus())) {
            throw new BusinessException(400, "当前操作状态为" + action.getStatus() + "，不能执行");
        }

        /*
         * 确认和实际执行可能存在时间间隔，
         * 因此执行前必须再次检查权限、能力版本和参数摘要。
         */
        String rejectionCode = validateFrozenAction(action, userId);
        if (rejectionCode != null) {
            return rejectAction(action, PendingActionStatus.CONFIRMED.getCode(), rejectionCode);
        }

        Map<String, Object> input;
        try {
            input = objectMapper.readValue(action.getInputJson(), new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            return rejectAction(action, PendingActionStatus.CONFIRMED.getCode(), "INPUT_JSON_INVALID");
        }

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

        LocalDateTime now = LocalDateTime.now();
        boolean claimed = lambdaUpdate()
                .eq(PendingAction::getId, action.getId())
                .eq(PendingAction::getUserId, userId)
                .eq(PendingAction::getStatus, PendingActionStatus.CONFIRMED.getCode())
                .set(PendingAction::getStatus, PendingActionStatus.EXECUTING.getCode())
                .set(PendingAction::getUpdatedAt, now)
                .update();

        if (!claimed) {
            PendingAction latest = getAction(runId, userId);
            if (isReusableResult(latest.getStatus())) {
                return latest;
            }
            throw new BusinessException(409, "操作状态已经发生变化，请刷新后重试");
        }

        PendingAction executingAction = getById(action.getId());
        actionAuditRecorder.record(executingAction, ActionAuditRecorder.EXECUTION_STARTED, null);

        ToolResult result;
        try {
            result = businessCapabilityExecutor.executeConfirmedWrite(
                    context, step, action.getIdempotencyKey());
        } catch (RuntimeException exception) {
            /*
             * 执行器抛出异常时无法判断请求是否已经到达业务系统。
             * 不能按普通失败处理，更不能自动重试。
             */
            return completeExecution(action, PendingActionStatus.UNKNOWN.getCode(),
                    ActionAuditRecorder.RESULT_UNKNOWN, "UNEXPECTED_EXECUTION_EXCEPTION",
                    "业务系统执行结果暂时无法确认，请人工核实。", null);
        }

        if (result == null) {
            return completeExecution(action, PendingActionStatus.UNKNOWN.getCode(),
                    ActionAuditRecorder.RESULT_UNKNOWN, "EMPTY_EXECUTION_RESULT",
                    "业务系统执行结果暂时无法确认，请人工核实。", null);
        }

        if (result.isSuccess()) {
            String outputJson;
            try {
                outputJson = toJson(result.getData());
            } catch (RuntimeException exception) {
                /*
                 * 外部WRITE已经成功返回，但本地结果保存失败。
                 * 此时不能把操作标记为可重试失败。
                 */
                return completeExecution(action, PendingActionStatus.UNKNOWN.getCode(),
                        ActionAuditRecorder.RESULT_UNKNOWN, "RESULT_SERIALIZATION_FAILED",
                        "业务系统可能已经完成操作，请人工核实。", null);
            }
            return completeExecution(action, PendingActionStatus.SUCCESS.getCode(),
                    ActionAuditRecorder.EXECUTION_SUCCEEDED, null, null, outputJson);
        }

        String errorCode = StringUtils.hasText(result.getErrorCode())
                ? result.getErrorCode()
                : "BUSINESS_WRITE_FAILED";

        if (isUncertainWriteResult(errorCode)) {
            return completeExecution(action, PendingActionStatus.UNKNOWN.getCode(),
                    ActionAuditRecorder.RESULT_UNKNOWN, errorCode,
                    "业务系统执行结果暂时无法确认，请人工核实。", null);
        }

        return completeExecution(action, PendingActionStatus.FAILED.getCode(),
                ActionAuditRecorder.EXECUTION_FAILED, errorCode,
                "业务操作未完成，请重新发起。", null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PendingAction confirmAndExecuteAction(String runId, String userId, String authorization) {
        PendingAction action = getAction(runId, userId);
        /*
         * SUCCESS、UNKNOWN、FAILED等结果直接复用。
         * UNKNOWN严禁通过重复确认重新调用外部WRITE接口。
         */
        if (isReusableResult(action.getStatus())
                && !PendingActionStatus.CONFIRMED.getCode().equals(action.getStatus())) {
            return action;
        }

        if (PendingActionStatus.PENDING.getCode().equals(action.getStatus())) {
            action = confirmAction(runId, userId);
        }
        if (PendingActionStatus.CONFIRMED.getCode().equals(action.getStatus())) {
            return executeConfirmedAction(runId, userId, authorization);
        }
        return action;
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
     * 计算固定参数摘要，确认和执行阶段使用相同算法重新校验。
     */
    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JDK不支持SHA-256", exception);
        }
    }

    /**
     * 重新校验待确认操作的可信上下文。
     *
     * 返回null表示通过，返回错误码表示必须拒绝执行。
     */
    private String validateFrozenAction(PendingAction action, String userId) {
        if (!StringUtils.hasText(action.getInputJson())
                || !StringUtils.hasText(action.getInputDigest())
                || !action.getInputDigest().equals(sha256(action.getInputJson()))) {
            return "INPUT_DIGEST_CHANGED";
        }

        Long traceCount = runTraceMapper.selectCount(Wrappers.<RunTrace>lambdaQuery()
                .eq(RunTrace::getRunId, action.getRunId())
                .eq(RunTrace::getUserId, userId));
        if (traceCount != 1L) {
            return "RUN_CONTEXT_CHANGED";
        }

        CapabilityDefinition capability;
        try {
            capability = capabilityDefinitionService.getEnabledByCode(action.getCapabilityCode());
        } catch (RuntimeException exception) {
            return "CAPABILITY_UNAVAILABLE";
        }
        if (capability == null) {
            return "CAPABILITY_UNAVAILABLE";
        }
        if (!"WRITE".equalsIgnoreCase(capability.getSideEffect())) {
            return "CAPABILITY_SIDE_EFFECT_CHANGED";
        }
        if (!Objects.equals(action.getCapabilityVersionId(), capability.getActiveVersionId())
                || !Objects.equals(action.getCapabilityConfigChecksum(), capability.getConfigChecksum())) {
            return "CAPABILITY_VERSION_CHANGED";
        }

        try {
            resourceAccessService.requireCapabilityAccess(action.getCapabilityCode(), userId);
        } catch (BusinessException exception) {
            return "PERMISSION_REVOKED";
        }
        return null;
    }

    /**
     * 将确认条件发生变化的操作原子转换为REJECTED。
     */
    private PendingAction rejectAction(PendingAction action, String expectedStatus, String rejectionCode) {
        LocalDateTime now = LocalDateTime.now();
        boolean updated = lambdaUpdate()
                .eq(PendingAction::getId, action.getId())
                .eq(PendingAction::getStatus, expectedStatus)
                .set(PendingAction::getStatus, PendingActionStatus.REJECTED.getCode())
                .set(PendingAction::getErrorMessage, "操作条件已经变化，请重新发起。")
                .set(PendingAction::getUpdatedAt, now)
                .update();

        PendingAction latest = getById(action.getId());
        if (updated) {
            // 中文注释：审计只保存固定错误码，不保存权限详情和原始参数。
            actionAuditRecorder.record(latest, ActionAuditRecorder.REJECTED, rejectionCode);
        }
        return latest;
    }

    /**
     * 原子保存一次WRITE执行结果并追加安全审计。
     */
    private PendingAction completeExecution(PendingAction action, String status, String eventType,
                                            String detail, String errorMessage, String outputJson) {
        LocalDateTime now = LocalDateTime.now();
        boolean updated = lambdaUpdate()
                .eq(PendingAction::getId, action.getId())
                .eq(PendingAction::getStatus, PendingActionStatus.EXECUTING.getCode())
                .set(PendingAction::getStatus, status)
                .set(PendingAction::getExecutedAt, now)
                .set(PendingAction::getErrorMessage, errorMessage)
                .set(PendingAction::getOutputJson, outputJson)
                .set(PendingAction::getUpdatedAt, now)
                .update();

        PendingAction latest = getById(action.getId());
        if (updated) {
            // 中文注释：审计只保存固定错误码，不保存异常详情和业务响应。
            actionAuditRecorder.record(latest, eventType, detail);
        }
        return latest;
    }

    /**
     * 判断外部WRITE结果是否无法确认。
     */
    private boolean isUncertainWriteResult(String errorCode) {
        return switch (errorCode) {
            case "BUSINESS_API_TIMEOUT_OR_NETWORK_ERROR",
                 "BUSINESS_API_HTTP_ERROR",
                 "BUSINESS_API_CALL_FAILED",
                 "BUSINESS_API_ERROR" -> true;
            default -> false;
        };
    }

    /**
     * 判断当前状态是否可以作为重复确认的稳定返回结果。
     */
    private boolean isReusableResult(String status) {
        return switch (status) {
            case "CONFIRMED", "EXECUTING", "SUCCESS", "FAILED",
                 "UNKNOWN", "REJECTED", "CANCELLED", "EXPIRED" -> true;
            default -> false;
        };
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
