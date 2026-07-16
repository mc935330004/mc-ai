package org.example.ai.agent.workflow.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 失败项目重试请求。
 */
@Data
public class WorkflowRetryFailedDTO {

    /**
     * 前端为每次点击生成UUID。
     *
     * 重复提交同一个requestId不会重复调用业务接口。
     */
    private String requestId;

    /**
     * 需要重试的FOREACH节点。
     */
    private String nodeId;

    /**
     * 使用当前页面上下文，不复用旧页面上下文。
     */
    private Map<String, Object> userContext =
            new LinkedHashMap<>();
}