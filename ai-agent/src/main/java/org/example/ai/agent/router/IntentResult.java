package org.example.ai.agent.router;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 意图路由结果。
 *
 * IntentRouter 会把用户问题分析成这个对象，
 * 后续 AgentOrchestrator 根据 routeType 决定走 RAG、业务查询还是混合问答。
 */
@Data
@Builder
public class IntentResult {

    /**
     * 路由类型。
     */
    private RouteType routeType;

    /**
     * 置信度。
     *
     * 取值范围建议为 0 ~ 1。
     */
    private double confidence;

    /**
     * 路由判断原因。
     *
     */
    private String reason;

    /**
     * 是否需要追问用户。
     */
    private boolean needClarify;

    /**
     * 追问内容。
     *
     * 当 needClarify = true 时返回给前端展示。
     */
    private String clarifyQuestion;

    /**
     * 命中的关键词。
     *
     * 用于调试路由规则是否合理。
     */
    private List<String> matchedKeywords;

    /**
     * 从问题中识别出的实体。
     *
     * 第一版可以先为空，后续可以放 projectName、contractNo、timeRange 等。
     */
    private Map<String, Object> entities;
}