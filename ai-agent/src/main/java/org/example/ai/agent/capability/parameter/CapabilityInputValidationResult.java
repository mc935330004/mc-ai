package org.example.ai.agent.capability.parameter;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 接口参数确定性校验结果。
 */
@Data
@Builder
public class CapabilityInputValidationResult {

    /**
     * 参数是否可以安全用于业务接口调用。
     */
    private boolean valid;

    /**
     * 根据 JSON Schema 清洗、转换并补齐默认值后的参数。
     */
    @Builder.Default
    private Map<String, Object> sanitizedInput =new LinkedHashMap<>();

    /**
     * 缺失的必填参数。
     */
    @Builder.Default
    private List<String> missingParameters = new ArrayList<>();

    /**
     * 参数类型、枚举、范围等错误。
     */
    @Builder.Default
    private List<String> validationErrors = new ArrayList<>();

    /**
     * 模型生成但 Schema 中不存在，已被删除的参数。
     */
    @Builder.Default
    private List<String> removedParameters = new ArrayList<>();
}