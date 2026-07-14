package org.example.ai.agent.capability.parameter;

import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.common.modelusage.ModelCallContext;

/**
 * 业务能力参数提取器。
 *
 * 职责非常单一：
 *
 * 1. 接收已经确定的唯一能力
 * 2. 根据该能力 inputSchemaJson 提取参数
 * 3. 不允许重新选择 capabilityCode
 */
public interface CapabilityParameterExtractor {

    /**
     * 从用户问题中提取指定能力需要的参数。
     *
     * @param userQuestion 用户原始问题
     * @param capability   已经确定的业务能力
     * @param callContext  模型调用上下文
     * @return 模型提取的原始参数
     */
    CapabilityParameterExtractionResult extract(
            String userQuestion,
            CapabilityDefinition capability,
            ModelCallContext callContext);
}