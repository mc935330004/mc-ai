package org.example.ai.agent.graph.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 业务能力调用节点配置。
 *
 * 普通能力：
 * pagination 为空，能力只执行一次。
 *
 * 分页查询能力：
 * pagination.enabled=true，由分页执行器连续调用能力，
 * 最终聚合所有页面的 records。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CapabilityNodeConfig(

        String capabilityCode,

        Map<String, Object> inputMapping,

        /**
         * 可选自动分页配置。
         *
         * 保持可选是为了兼容已有工作流，
         * 同时不影响新增、修改、删除等非分页能力。
         */
        CapabilityPaginationConfig pagination) implements GraphNodeConfig {

     public CapabilityNodeConfig {

        /*
         * 对输入映射进行防御性复制，
         * 避免编译完成后被外部代码修改。
         */
        inputMapping =inputMapping == null
                        ? Map.of()
                        : Collections.unmodifiableMap(
                        new LinkedHashMap<>(inputMapping));
    }
}