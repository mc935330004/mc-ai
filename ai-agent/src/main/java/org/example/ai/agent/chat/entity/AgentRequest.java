package org.example.ai.agent.chat.entity;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

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
}