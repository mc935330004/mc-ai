package org.example.ai.agent.capability.parameter;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型参数提取结果。
 *
 * 注意：
 * 该结果不能直接用于调用业务接口。
 * 必须再经过 CapabilityInputSchemaValidator 校验和清洗。
 */
@Data
public class CapabilityParameterExtractionResult {

    /**
     * 模型从用户问题中提取到的参数。
     */
    private Map<String, Object> input = new LinkedHashMap<>();

    /**
     * 模型认为用户没有提供的参数。
     *
     * 该字段仅用于辅助排查。
     * 后端最终以 JSON Schema required 校验结果为准。
     */
    private List<String> missingParameters = new ArrayList<>();

    /**
     * 参数提取说明。
     */
    private String reason;
}