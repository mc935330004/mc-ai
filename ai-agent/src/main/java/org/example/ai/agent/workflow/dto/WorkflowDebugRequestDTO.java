package org.example.ai.agent.workflow.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
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

    /**
     * 兼容扁平调试参数。
     *
     * input、userContext 是 DTO 已声明字段，
     * Jackson 会正常绑定，不会进入本方法。
     *
     * projectKeys 等未声明的顶层业务字段，
     * 会通过本方法自动放入 input。
     */
    @JsonAnySetter
    public void addFlatInput(
            String fieldName,
            Object value) {

        if (input == null) {
            input = new LinkedHashMap<>();
        }

        input.put(fieldName, value);
    }
}