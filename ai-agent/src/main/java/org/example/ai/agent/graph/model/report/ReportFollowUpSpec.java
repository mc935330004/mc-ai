package org.example.ai.agent.graph.model.report;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报告完成后的通用业务追问配置。
 *
 * 单个只读能力使用 CAPABILITY，
 * 多节点编排使用 WORKFLOW。
 */
public record ReportFollowUpSpec(
        boolean enabled,
        String prompt,
        String targetType,
        String targetCode,
        String optionRowPath,
        String optionKeyPath,
        String optionLabelPath,
        Map<String, Object> inputMapping) {

    public ReportFollowUpSpec {

        /*
         * 保留输入映射顺序，
         * 方便工作流源码展示和配置排查。
         */
        inputMapping = inputMapping == null
                ? Map.of()
                : Collections.unmodifiableMap(
                new LinkedHashMap<>(inputMapping)
        );
    }
}