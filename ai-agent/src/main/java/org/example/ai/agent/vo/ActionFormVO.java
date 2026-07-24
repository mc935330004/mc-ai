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
     * WRITE能力发布快照中的输入Schema。
     */
    private JsonNode schema;

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