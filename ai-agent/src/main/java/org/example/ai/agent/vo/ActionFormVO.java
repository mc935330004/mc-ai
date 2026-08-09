package org.example.ai.agent.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WRITE参数收集表单事件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionFormVO {

    /**
     * 已发布工作流编码。
     */
    private String workflowCode;

    /**
     * 工作流发布版本ID。
     */
    private Long workflowVersionId;

    /**
     * WRITE能力编码。
     */
    private String capabilityCode;

    /**
     * WRITE能力发布版本ID。
     */
    private Long capabilityVersionId;

    /**
     * WRITE能力名称。
     */
    private String capabilityName;

    /**
     * WRITE能力发布快照中的输入 Schema。
     *
     *  
     * Spring Boot 4 的 HTTP/SSE 序列化器不能直接正确处理
     * Jackson 2 的 JsonNode，因此在 SSE 边界使用标准 JSON 字符串。
     * 前端动态表单解析器已经支持 JSON 字符串，不需要修改前端协议。
     */
    private String schema;

    /**
     * 大模型已经提取出的初始值。
     */
    @Builder.Default
    private Map<String, Object> initialValue =
            new LinkedHashMap<>();

    /**
     * 需要用户补充的信息。
     */
    private String clarifyQuestion;
}