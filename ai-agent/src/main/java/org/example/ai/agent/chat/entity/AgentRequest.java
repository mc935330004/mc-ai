package org.example.ai.agent.chat.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
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
     * SSE 协议版本。
     *
     * 该字段只能由 Controller 根据请求头写入，
     * 不允许客户端通过 JSON 请求体直接伪造。
     */
    @JsonIgnore
    private Integer streamVersion;

    /**
     * 中文注释：前端选择的大模型编码，只允许后端配置中的模型。
     */
    private String modelCode;

    /**
     * 中文注释：后端注入的最近历史对话，不允许前端传入伪造。
     */
    @JsonIgnore
    private String conversationMemory;

    /**
     * 中文注释：后端根据结构化会话状态补全后的问题，
     * 不接收前端传值，也不写入聊天记录。
     */
    @JsonIgnore
    private String contextualQuestion;
    /**
     * 中文注释：上一轮成功查询的参数，只允许后端注入。
     */
    @JsonIgnore
    private Map<String, Object> inheritedInput = new LinkedHashMap<>();

    /**
     * 中文注释：上一轮成功查询使用的工作流编码。
     */
    @JsonIgnore
    private String previousWorkflowCode;

    /**
     * 中文注释：上一轮成功查询使用的能力编码。
     */
    @JsonIgnore
    private String previousCapabilityCode;
    /**
     * 中文注释：表示用户本轮明确要求清除历史上下文。
     */
    @JsonIgnore
    private boolean contextReset;
    /**
     * 中文注释：上一轮工作流安全结果快照ID。
     *
     * 只能由后端会话状态注入，
     * 禁止客户端通过请求JSON指定。
     */
    @JsonIgnore
    private String resultArtifactId;

    /**
     * 中文注释：当前问题是否属于上一轮结果分析追问。
     */
    @JsonIgnore
    private boolean resultAnalysisRequest;
    /**
     * 中文注释：路由和检索优先使用补全问题，用户展示仍使用原始问题。
     */
    @JsonIgnore
    public String getEffectiveQuestion() {
        return contextualQuestion != null
                && !contextualQuestion.isBlank()
                ? contextualQuestion
                : userQuestion;
    }
}