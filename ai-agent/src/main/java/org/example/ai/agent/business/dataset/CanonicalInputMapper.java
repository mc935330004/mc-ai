package org.example.ai.agent.business.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 将可信规范参数按数据集持久化的显式映射转换为单个发布工作流的输入。
 *
 * 该类不选择工作流，也不接受模型提供的目标参数名。
 */
@Component
public final class CanonicalInputMapper {

    private final ReportDatasetValidator validator;

    public CanonicalInputMapper(ReportDatasetValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator不能为空");
    }

    /**
     * 返回结构防御复制且不可变的新映射；不同工作流必须分别调用并传入各自映射和 Schema。
     */
    public Map<String, Object> map(
            Map<String, Object> canonicalValues,
            Map<String, String> explicitMapping,
            JsonNode inputSchema) {
        Map<String, Object> canonicalSnapshot = canonicalValues == null
                ? Map.of()
                : new LinkedHashMap<>(canonicalValues);
        Map<String, String> mappingSnapshot = explicitMapping == null
                ? Map.of()
                : new LinkedHashMap<>(explicitMapping);
        validator.validateCanonicalInputMapping(
                canonicalSnapshot,
                mappingSnapshot,
                inputSchema
        );

        Map<String, Object> workflowInput = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : canonicalSnapshot.entrySet()) {
            workflowInput.put(mappingSnapshot.get(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(workflowInput);
    }
}
