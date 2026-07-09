package org.example.ai.agent.plan;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 动态能力规划结果。
 *
 * 根据用户问题，从 ai_capability_definition 中选择一个能力，
 * 并生成该能力需要的入参。
 */
@Data
public class DynamicCapabilityPlan {

    /**
     * 选中的能力编码。
     */
    private String capabilityCode;

    /**
     * 能力入参。
     */
    private Map<String, Object> input = new LinkedHashMap<>();

    /**
     * 选择原因，方便调试。
     */
    private String reason;
}