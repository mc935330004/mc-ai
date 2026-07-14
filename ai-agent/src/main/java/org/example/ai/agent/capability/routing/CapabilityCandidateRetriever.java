package org.example.ai.agent.capability.routing;

import java.util.List;

/**
 * 业务能力候选召回器。
 *
 * 第一阶段使用本地文本召回。
 * 第三阶段接入 PGVector 后，可以新增 HybridCapabilityCandidateRetriever，
 * 不需要修改上层 DynamicCapabilityPlanner。
 */
public interface CapabilityCandidateRetriever {

    /**
     * 根据用户问题召回最相关的业务能力。
     *
     * @param userQuestion 用户原始问题
     * @return 已按召回分数从高到低排序的候选能力
     */
    List<CapabilityCandidate> retrieve(String userQuestion);
}