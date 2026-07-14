package org.example.ai.agent.capability.routing;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 业务能力路由配置。
 *
 * 第一阶段先把候选召回、模型精排和安全闸门的阈值配置化，
 * 避免把这些参数散落在 Java 代码中。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent.capability-routing")
public class CapabilityRoutingProperties {

    /**
     * 初步召回后最多交给大模型判断的候选能力数量。
     *
     * 不建议设置太大。
     * 候选太多时，大模型仍然容易在相似接口之间跑串。
     */
    private int topK = 5;

    /**
     * 候选能力最低初步召回分数。
     *
     * 第一阶段使用本地字符匹配算法，因此阈值不宜过高。
     * 第二阶段接入向量召回后，可以重新调整。
     */
    private double minRecallScore = 0.06D;

    /**
     * 大模型选择某个能力时的最低置信度。
     *
     * 低于该值时，不调用业务接口，转为追问用户。
     */
    private double minConfidence = 0.75D;

    /**
     * 第一名与第二名之间的最低分差。
     *
     * 例如：
     * 第一名 0.82，第二名 0.78，分差只有 0.04，
     * 说明两个能力非常接近，此时应该追问，不应该强行选择。
     */
    private double minScoreMargin = 0.12D;

    /**
     * 是否启用关键词和向量混合召回。
     *
     * 关闭后自动退回第一阶段的关键词召回。
     */
    private boolean hybridEnabled = true;

    /**
     * 关键词召回的初始候选数量。
     *
     * 初始数量可以大于最终 topK，
     * 混合评分后再截取最终候选。
     */
    private int keywordTopK = 10;

    /**
     * 向量召回候选数量。
     */
    private int vectorTopK = 10;

    /**
     * 向量检索最低相似度。
     *
     * 不同 Embedding 模型的分数分布不同，
     * 建议通过真实测试集调整。
     */
    private double minVectorScore = 0.45D;

    /**
     * 关键词召回权重。
     */
    private double keywordWeight = 0.40D;

    /**
     * 向量召回权重。
     */
    private double vectorWeight = 0.60D;

    /**
     * 同时被关键词和向量召回时的加分。
     */
    private double dualHitBonus = 0.05D;
}