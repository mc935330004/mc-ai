package org.example.ai.agent.tool.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.answer.extractor.DictionaryFactExtractor;
import org.example.ai.agent.answer.model.AnswerFact;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.invocation.runtime.CapabilityHttpInvoker;
import org.example.ai.agent.capability.invocation.runtime.CapabilityHttpRequest;
import org.example.ai.agent.capability.invocation.runtime.CapabilityHttpRequestBuilder;
import org.example.ai.agent.capability.invocation.runtime.CapabilityInvocationContext;
import org.example.ai.agent.capability.invocation.runtime.CapabilityInvocationContextFactory;
import org.example.ai.agent.capability.invocation.runtime.CapabilityInvocationException;
import org.example.ai.agent.capability.invocation.runtime.CapabilityResponseInterpreter;
import org.example.ai.agent.capability.invocation.runtime.ResponseInterpretationResult;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.tool.BusinessCapabilityExecutor;
import org.example.ai.agent.tool.FieldMeta;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.example.ai.agent.tool.ToolResult;
import org.example.ai.agent.tool.projection.CapabilityOutputProjection;
import org.example.ai.agent.tool.projection.CapabilityOutputProjector;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务能力统一执行器。
 *
 * 核心职责：
 * 1. 根据capabilityCode加载已发布能力；
 * 2. 校验能力副作用等级；
 * 3. 构造安全调用上下文；
 * 4. 调用真实业务系统接口；
 * 5. 判断业务响应是否成功；
 * 6. 根据字段字典生成机器数据和展示数据；
 * 7. 提取大模型回答所需的标准事实。
 *
 * 本执行器不负责GraphSpec节点调度，
 * GraphSpec调度由CapabilityGraphNodeExecutor负责。
 */
@Service
@RequiredArgsConstructor
public class BusinessCapabilityExecutorImpl
        implements BusinessCapabilityExecutor {

    private final CapabilityDefinitionService capabilityDefinitionService;

    private final FieldDictionaryMapper fieldDictionaryMapper;

    private final CapabilityInvocationContextFactory invocationContextFactory;

    private final CapabilityHttpRequestBuilder httpRequestBuilder;

    private final CapabilityHttpInvoker httpInvoker;

    private final CapabilityResponseInterpreter responseInterpreter;

    /**
     * 根据字段字典提取标准事实。
     */
    private final DictionaryFactExtractor
            dictionaryFactExtractor;

    /**
     * 根据字段字典生成机器数据和中文展示数据。
     */
    private final CapabilityOutputProjector
            outputProjector;

    /**
     * 普通工具执行入口。
     *
     * WRITE能力不能通过本入口自动执行。
     */
    @Override
    public ToolResult execute(
            ToolExecutionContext context,
            PlanStep step) {

        return executeInternal( context, step, false,null,false);
    }

    /**
     * 已经过用户确认的写操作入口。
     */
    @Override
    public ToolResult executeConfirmedWrite(
            ToolExecutionContext context,
            PlanStep step,
            String idempotencyKey) {

        if (!StringUtils.hasText(
                idempotencyKey)) {

            /*
             * 这里只记录公开业务输入，
             * 不能把包含Authorization的ToolExecutionContext
             * 写入ToolResult.input。
             */
            return fail(
                    step,
                    safePublicInput(step),
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "写操作缺少幂等键"
            );
        }

        return executeInternal(
                context,
                step,
                true,
                idempotencyKey,
                false
        );
    }

    /**
     * 管理端READ能力测试入口。
     *
     * 管理端测试可以临时查看raw，
     * 普通Agent运行不能保存raw。
     */
    @Override
    public ToolResult executeReadTest(
            ToolExecutionContext context,
            PlanStep step) {

        return executeInternal(
                context,
                step,
                false,
                null,
                true
        );
    }

    /**
     * 统一业务能力执行入口。
     */
    private ToolResult executeInternal(
            ToolExecutionContext context,
            PlanStep step,
            boolean confirmedWrite,
            String idempotencyKey,
            boolean adminReadTest) {

        String capabilityCode =
                step.getCapabilityCode();

        try {
            CapabilityDefinition capability =
                    loadCapability(
                            capabilityCode,
                            adminReadTest
                    );

            if (capability == null) {
                return fail(
                        step,
                        safePublicInput(step),
                        "CAPABILITY_NOT_FOUND",
                        "能力不存在或未启用："
                                + capabilityCode
                );
            }

            ToolResult sideEffectFailure =
                    validateSideEffect(
                            capability,
                            step,
                            confirmedWrite
                    );

            if (sideEffectFailure != null) {
                return sideEffectFailure;
            }

            /*
             * 把工作流输入、上游变量和可信安全上下文
             * 转换为能力绑定可以读取的调用上下文。
             */
            CapabilityInvocationContext
                    invocationContext =
                    invocationContextFactory.create(
                            context,
                            step
                    );

            CapabilityHttpRequest httpRequest =
                    httpRequestBuilder.build(
                            capability,
                            invocationContext,
                            idempotencyKey
                    );

            Object raw =
                    httpInvoker.invoke(httpRequest);

            /*
             * HTTP 2xx只表示传输成功。
             *
             * 还要根据responseBindingJson判断：
             * 1. 业务状态码是否成功；
             * 2. data路径在哪里；
             * 3. 返回数据是否为空。
             */
            ResponseInterpretationResult interpreted =
                    responseInterpreter.interpret(
                            capability,
                            raw,
                            adminReadTest
                    );

            if (!interpreted.success()) {
                return buildBusinessFailure(
                        capabilityCode,
                        step,
                        httpRequest,
                        interpreted,
                        raw,
                        adminReadTest
                );
            }

            List<FieldMeta> fields =
                    loadFieldMetas(
                            capabilityCode
                    );

            /*
             * 生成双通道安全数据：
             *
             * workflowData：
             * 使用id、projectCode等稳定机器字段。
             *
             * displayData：
             * 使用记录ID、项目编码等中文展示字段。
             */
            CapabilityOutputProjection projection =
                    outputProjector.project(
                            raw,
                            interpreted.data(),
                            fields
                    );

            List<AnswerFact> facts =
                    dictionaryFactExtractor.extract(
                            capabilityCode,
                            raw,
                            fields
                    );

            return ToolResult.builder()
                    .success(true)
                    .capabilityCode(
                            capabilityCode
                    )
                    .outputKey(
                            step.getOutputKey()
                    )
                    .businessCode(
                            interpreted.businessCode()
                    )
                    .businessMessage(
                            interpreted.businessMessage()
                    )
                    .emptyData(
                            interpreted.emptyData()
                    )

                    /*
                     * data继续保存展示视图，
                     * 兼容已有工作流和前端页面。
                     */
                    .data(
                            projection.displayData()
                    )

                    /*
                     * 新工作流下游节点必须读取workflowData。
                     */
                    .workflowData(
                            projection.workflowData()
                    )

                    /*
                     * 前端和大模型展示读取displayData。
                     */
                    .displayData(
                            projection.displayData()
                    )
                    .fields(fields)
                    .facts(facts)
                    .summary(
                            interpreted.emptyData()
                                    ? "业务能力调用成功，但未查询到数据："
                                      + capability.getCapabilityName()
                                    : "业务能力调用成功："
                                      + capability.getCapabilityName()
                    )

                    /*
                     * 只有管理端测试可以临时保留raw。
                     */
                    .raw(
                            adminReadTest
                                    ? raw
                                    : null
                    )
                    .input(
                            httpRequest.getAuditInput()
                    )
                    .build();

        } catch (CapabilityInvocationException exception) {
            /*
             * CapabilityInvocationException中的消息
             * 必须是已经处理过的安全错误消息。
             */
            return fail(
                    step,
                    safePublicInput(step),
                    exception.getErrorCode(),
                    exception.getMessage()
            );

        } catch (Exception exception) {
            /*
             * 未知异常不能直接返回exception.getMessage()。
             *
             * 异常中可能包含URL Query、Header、
             * 认证信息或完整业务响应。
             */
            return fail(
                    step,
                    safePublicInput(step),
                    "BUSINESS_API_ERROR",
                    "业务能力调用失败"
            );
        }
    }

    /**
     * 加载能力定义。
     *
     * 普通运行只能加载已启用、已发布能力；
     * 管理端测试允许加载尚未发布的草稿能力。
     */
    private CapabilityDefinition loadCapability(
            String capabilityCode,
            boolean adminReadTest) {

        if (adminReadTest) {
            return capabilityDefinitionService
                    .lambdaQuery()
                    .eq(
                            CapabilityDefinition
                                    ::getCapabilityCode,
                            capabilityCode
                    )
                    .one();
        }

        return capabilityDefinitionService
                .getEnabledByCode(
                        capabilityCode
                );
    }

    /**
     * 校验能力副作用等级。
     *
     * @return 校验通过返回null，失败返回ToolResult
     */
    private ToolResult validateSideEffect(
            CapabilityDefinition capability,
            PlanStep step,
            boolean confirmedWrite) {

        String sideEffect =
                capability.getSideEffect();

        /*
         * DANGEROUS能力当前始终禁止自动执行。
         */
        if ("DANGEROUS".equalsIgnoreCase(
                sideEffect)) {

            return fail(
                    step,
                    safePublicInput(step),
                    "DANGEROUS_CAPABILITY_NOT_ALLOWED",
                    "危险能力禁止执行："
                            + capability.getCapabilityCode()
            );
        }

        /*
         * WRITE能力必须从确认写操作入口进入。
         */
        if ("WRITE".equalsIgnoreCase(sideEffect)
                && !confirmedWrite) {

            return fail(
                    step,
                    safePublicInput(step),
                    "WRITE_CONFIRM_REQUIRED",
                    "写操作必须经过用户确认："
                            + capability.getCapabilityCode()
            );
        }

        /*
         * 防止错误配置绕过安全检查。
         */
        if (!"READ".equalsIgnoreCase(sideEffect)
                && !"WRITE".equalsIgnoreCase(
                sideEffect)) {

            return fail(
                    step,
                    safePublicInput(step),
                    "INVALID_SIDE_EFFECT",
                    "不支持的能力副作用类型："
                            + sideEffect
            );
        }

        return null;
    }

    /**
     * 构建业务系统返回失败的结果。
     */
    private ToolResult buildBusinessFailure(
            String capabilityCode,
            PlanStep step,
            CapabilityHttpRequest httpRequest,
            ResponseInterpretationResult interpreted,
            Object raw,
            boolean adminReadTest) {

        return ToolResult.builder()
                .success(false)
                .capabilityCode(
                        capabilityCode
                )
                .outputKey(
                        step.getOutputKey()
                )
                .businessCode(
                        interpreted.businessCode()
                )
                .businessMessage(
                        interpreted.businessMessage()
                )
                .errorCode(
                        interpreted.errorCode()
                )
                .errorMessage(
                        interpreted.errorMessage()
                )
                .summary(
                        "业务能力调用失败："
                                + interpreted.errorMessage()
                )
                .input(
                        httpRequest.getAuditInput()
                )

                /*
                 * 普通Agent运行不能保存完整raw。
                 */
                .raw(
                        adminReadTest
                                ? raw
                                : null
                )
                .build();
    }

    /**
     * 提取允许写入失败结果的公开输入。
     */
    private Map<String, Object> safePublicInput(
            PlanStep step) {

        if (step == null
                || CollectionUtils.isEmpty(
                step.getInput())) {

            return Map.of();
        }

        return new LinkedHashMap<>(
                step.getInput()
        );
    }

    /**
     * 加载已发布字段字典。
     */
    private List<FieldMeta> loadFieldMetas(
            String capabilityCode) {

        List<FieldDictionary> dictionaries =
                fieldDictionaryMapper.selectList(
                        new LambdaQueryWrapper<FieldDictionary>()
                                .eq(
                                        FieldDictionary
                                                ::getCapabilityCode,
                                        capabilityCode
                                )
                                .eq(
                                        FieldDictionary
                                                ::getPublishStatus,
                                        "PUBLISHED"
                                )
                                .orderByAsc(
                                        FieldDictionary
                                                ::getDisplayOrder
                                )
                                .orderByAsc(
                                        FieldDictionary
                                                ::getId
                                )
                );

        return dictionaries.stream()
                .map(item ->
                        FieldMeta.builder()
                                .name(
                                        item.getFieldName()
                                )
                                .cnName(
                                        item.getFieldCnName()
                                )
                                .path(
                                        item.getFieldPath()
                                )
                                .type(
                                        item.getFieldType()
                                )
                                .format(
                                        item.getDisplayFormat()
                                )
                                .meaning(
                                        item.getBusinessMeaning()
                                )
                                .requiredOutput(
                                        defaultInteger(
                                                item.getRequiredOutput(),
                                                0
                                        )
                                )
                                .visible(
                                        defaultInteger(
                                                item.getVisible(),
                                                1
                                        )
                                )
                                .displayOrder(
                                        defaultInteger(
                                                item.getDisplayOrder(),
                                                0
                                        )
                                )
                                .displayGroup(
                                        item.getDisplayGroup()
                                )
                                .nullDisplayText(
                                        StringUtils.hasText(
                                                item.getNullDisplayText()
                                        )
                                                ? item.getNullDisplayText()
                                                .trim()
                                                : "当前数据中未提供"
                                )
                                .build()
                )
                .toList();
    }

    /**
     * Integer空值默认处理。
     */
    private int defaultInteger(
            Integer value,
            int defaultValue) {

        return value == null
                ? defaultValue
                : value;
    }

    /**
     * 构建统一失败结果。
     */
    public ToolResult fail(
            PlanStep step,
            Object input,
            String errorCode,
            String errorMessage) {

        return ToolResult.builder()
                .success(false)
                .capabilityCode(
                        step == null
                                ? null
                                : step.getCapabilityCode()
                )
                .outputKey(
                        step == null
                                ? null
                                : step.getOutputKey()
                )
                .errorCode(
                        errorCode
                )
                .errorMessage(
                        errorMessage
                )
                .summary(
                        "业务能力调用失败："
                                + errorMessage
                )
                .input(input)
                .build();
    }
}