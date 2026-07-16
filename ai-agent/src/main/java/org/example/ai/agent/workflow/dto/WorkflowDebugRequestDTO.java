package org.example.ai.agent.workflow.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 草稿调试请求。
 */
@Data
public class WorkflowDebugRequestDTO {

    private Map<String, Object> input =
            new LinkedHashMap<>();

    private Map<String, Object> userContext =
            new LinkedHashMap<>();
}