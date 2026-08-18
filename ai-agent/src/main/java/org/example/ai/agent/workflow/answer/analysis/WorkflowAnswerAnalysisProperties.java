package org.example.ai.agent.workflow.answer.analysis;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 报告分析速度优化配置。
 *
 * 前缀：ai.workflow.answer.analysis
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ai.workflow.answer.analysis")
public class WorkflowAnswerAnalysisProperties {

    /**
     * 是否启用模型判定。
     *
     * true 时：DATA_QUERY 报告由模型判断是否需要分析；
     * false 时：回退旧逻辑，只按 queryType 决定（ANALYSIS_REPORT 才分析）。
     */
    private boolean decisionEnabled = true;

    /**
     * 小数据快路径阈值。
     *
     * 分块数不超过该值时，一次性调用模型生成结构化分析，
     * 跳过"逐块消费 + 分层汇总"的多轮调用。
     */
    private int lightweightMaxChunks = 3;

    /**
     * 分块消费受控并发数。
     *
     * 大数据量时最多同时消费的分块数量，避免瞬时打爆模型服务。
     */
    private int concurrency = 4;

    /**
     * 分析总超时秒数（不含基础报告生成时间）。
     *
     * 超时后保留基础业务报告，AI 分析区域按失败降级。
     */
    private int timeoutSeconds = 30;

    /**
     * 判定调用使用的数据抽样最大字符数。
     *
     * 判定只读抽样数据，不读完整业务数据，保证判定快速且不泄露过多内容。
     */
    private int sampleMaxChars = 2000;

    @PostConstruct
    public void validate() {
        if (concurrency < 1 || concurrency > 16) {
            throw new IllegalStateException(
                    "ai.workflow.answer.analysis.concurrency必须在1~16之间"
            );
        }
        if (lightweightMaxChunks < 1) {
            throw new IllegalStateException(
                    "ai.workflow.answer.analysis.lightweight-max-chunks必须大于0"
            );
        }
        if (timeoutSeconds < 5 || timeoutSeconds > 120) {
            throw new IllegalStateException(
                    "ai.workflow.answer.analysis.timeout-seconds必须在5~120之间"
            );
        }
        if (sampleMaxChars < 200) {
            throw new IllegalStateException(
                    "ai.workflow.answer.analysis.sample-max-chars不能小于200"
            );
        }
    }
}
