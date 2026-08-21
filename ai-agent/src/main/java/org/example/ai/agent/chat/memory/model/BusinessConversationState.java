package org.example.ai.agent.chat.memory.model;

import lombok.Data;

import java.time.LocalDateTime;
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
     * 上一轮结果中的项目展示顺序。
     */
    private List<String> displayObjectIds = new ArrayList<>();

    /**
     * 上一轮规则判定命中的风险项目。
     */
    private List<String> riskObjectIds = new ArrayList<>();

    /**
     * 因字段缺失等原因无法确定风险状态的项目。
     */
    private List<String> unknownObjectIds = new ArrayList<>();

    /**
     * 当前追问明确聚焦的项目编码。
     */
    private String focusedObjectId;

    /**
     * 上一轮实际使用的展示方式：ANSWER或REPORT。
     */
    private String lastPresentationMode;

    /**
     * 最近一次风险判定对应的运行编号。
     */
    private String riskEvaluationRunId;

    /**
     * 多项目指代不明确时保存原始问题。
     *
     * 用户补充项目编码后，可以恢复原问题，
     * 避免只记住项目编码却忘记用户要查询什么。
     */
    private String pendingContextQuestion;

    /**
     * 状态最后更新时间。
     */
    private LocalDateTime updatedAt;

    /**
     * 当前报告完成后等待用户回答的通用业务追问。
     *
     * 该字段保存在 state_json 中，
     * 不需要修改 ai_conversation_state 表结构。
     */
    private PendingReportFollowUp pendingReportFollowUp;
}