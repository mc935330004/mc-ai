package org.example.ai.agent.graph.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.example.ai.agent.common.enums.MergeMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MERGE节点配置。
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record MergeNodeConfig(
        MergeMode mode,
        Map<String, String> mappings,
        List<String> sources)
        implements GraphNodeConfig {

    public MergeNodeConfig {
        mode = mode == null
                ? MergeMode.OBJECT
                : mode;

        mappings = mappings == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(mappings));

        sources = sources == null? List.of()
                : Collections.unmodifiableList(new ArrayList<>(sources));
    }
}