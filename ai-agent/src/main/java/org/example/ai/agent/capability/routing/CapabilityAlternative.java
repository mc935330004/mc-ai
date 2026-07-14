package org.example.ai.agent.capability.routing;

import lombok.Data;

/**
 * 大模型返回的备选能力。
 *
 * 主要用于计算第一名和第二名的分差，
 * 同时把没有选择其他接口的原因记录到 Trace。
 */
@Data
public class CapabilityAlternative {

    /**
     * 备选能力编码。
     */
    private String capabilityCode;

    /**
     * 模型认为该备选能力的匹配分数。
     */
    private double score;

    /**
     * 没有选择该能力的原因。
     */
    private String rejectReason;
}