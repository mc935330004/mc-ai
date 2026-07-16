package org.example.ai.agent.graph.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 能力调用节点配置。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CapabilityNodeConfig( String capabilityCode, Map<String, Object> inputMapping)
        implements GraphNodeConfig {

    public CapabilityNodeConfig {
        inputMapping = inputMapping == null ? Map.of()
                : Collections.unmodifiableMap( new LinkedHashMap<>(inputMapping ));
    }
}