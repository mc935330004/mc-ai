package org.example.ai.agent.chat.memory.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文注释：保存上一轮成功业务请求的结构化状态，不保存认证信息。
 */
@Data
public class BusinessConversationState {

    private String activeObjectType;
    private List<String> activeObjectIds = new ArrayList<>();
    private String businessTopic;
    private String routeType;
    private String workflowCode;
    private Long workflowVersionId;
    private String capabilityCode;
    private Map<String, Object> lastInput = new LinkedHashMap<>();
    private String lastRunId;
    private Long factMessageId;
}