package org.example.ai.agent.chat.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;

import java.util.ArrayList;
import java.util.LinkedHashMap;

import java.util.List;
import java.util.Map;

@Data
public class AgentRequest {

    /**
     * 会话id
     */
    private String conversationId;

    /**
     * 用户id
     */
    private String userId;

    /**
     * 用户问题
     */
    @NotBlank(message = "用户问题不能为空")
    private String userQuestion;

    /**
     * 分类ids
     */
    private List<Long> categoryIds;

    /**
     * 文档ids
     */
    private List<Long> documentIds;

    /**
     * topk
     */
    private Integer topK;

    /**
     * 最小分数
     */
    private Double minScore;

    /**
     * 分页上下文
     */
    private Map<String, Object> pageContext;

    /**
     * 额外信息
     */
    private Map<String, Object> extra;

    /**
     * 当前请求认证信息，只用于服务内部调用业务系统
     */
    @JsonIgnore
    private String authorization;

    /**
     * 请求线程中解析出的可信知识库身份。
     *
     * Agent主体在异步线程执行，不能延迟读取HttpServletRequest，
     * 因此该字段只能由后端Controller注入。
     */
    @JsonIgnore
    private KnowledgeAccessPrincipal knowledgeAccessPrincipal;

    /**
     * SSE 协议版本。
     *
     * 该字段只能由 Controller 根据请求头写入，
     * 不允许客户端通过 JSON 请求体直接伪造。
     */
    @JsonIgnore
    private Integer streamVersion;

    /**
     *  前端选择的大模型编码，只允许后端配置中的模型。
     */
    private String modelCode;

    /**
     *  后端注入的最近历史对话，不允许前端传入伪造。
     */
    @JsonIgnore
    private String conversationMemory;

    /**
     *  后端根据结构化会话状态补全后的问题，
     * 不接收前端传值，也不写入聊天记录。
     */
    @JsonIgnore
    private String contextualQuestion;
    /**
     *  上一轮成功查询的参数，只允许后端注入。
     */
    @JsonIgnore
    private Map<String, Object> inheritedInput = new LinkedHashMap<>();

    /**
     *  上一轮成功查询使用的工作流编码。
     */
    @JsonIgnore
    private String previousWorkflowCode;

    /**
     *  上一轮成功查询使用的能力编码。
     */
    @JsonIgnore
    private String previousCapabilityCode;
    /**
     *  表示用户本轮明确要求清除历史上下文。
     */
    @JsonIgnore
    private boolean contextReset;
    /**
     *  上一轮工作流安全结果快照ID。
     *
     * 只能由后端会话状态注入，
     * 禁止客户端通过请求JSON指定。
     */
    @JsonIgnore
    private String resultArtifactId;

    /**
     *  当前问题是否属于上一轮结果分析追问。
     */
    @JsonIgnore
    private boolean resultAnalysisRequest;

    /**
     * 上一轮结果中的项目展示顺序，只允许后端注入。
     */
    @JsonIgnore
    private List<String> displayObjectIds = new ArrayList<>();

    /**
     * 上一轮风险项目，只允许后端注入。
     */
    @JsonIgnore
    private List<String> riskObjectIds = new ArrayList<>();

    /**
     * 上一轮风险状态未知的项目，只允许后端注入。
     */
    @JsonIgnore
    private List<String> unknownObjectIds = new ArrayList<>();

    /**
     * 当前聚焦项目，只允许后端通过会话状态解析。
     */
    @JsonIgnore
    private String focusedObjectId;

    /**
     * 上一轮实际展示方式。
     */
    @JsonIgnore
    private String lastPresentationMode;

    /**
     * 上一轮风险判定运行编号。
     */
    @JsonIgnore
    private String riskEvaluationRunId;

    /**
     * 上下文无法唯一确定项目时，直接返回给用户的追问。
     */
    @JsonIgnore
    private String contextClarificationQuestion;

    /**
     *  路由和检索优先使用补全问题，用户展示仍使用原始问题。
     */
    @JsonIgnore
    public String getEffectiveQuestion() {
        return contextualQuestion != null
                && !contextualQuestion.isBlank()
                ? contextualQuestion
                : userQuestion;
    }
}
