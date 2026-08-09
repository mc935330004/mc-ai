package org.example.ai.agent.chat.memory.model;

import lombok.Data;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *  保存上一轮成功业务请求的结构化状态，不保存认证信息。
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
    /**
     * 当前会话是否正在等待用户补充查询参数。
     *
     * 字段保存在 state_json 中，不需要修改数据库表结构。
     */
    private boolean awaitingClarification;
    /**
     *  
     * 上一轮工作流安全结果快照ID。
     * 后续追问统计时直接读取该快照，不再重新路由工作流。
     */
    private String resultArtifactId;

    /**
     * 当前报告完成后等待用户回答的通用业务追问。
     *
     * 该字段保存在 state_json 中，
     * 不需要修改 ai_conversation_state 表结构。
     */
    private PendingReportFollowUp pendingReportFollowUp;
}