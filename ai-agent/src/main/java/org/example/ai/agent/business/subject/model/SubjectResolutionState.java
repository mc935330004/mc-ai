package org.example.ai.agent.business.subject.model;

/**
 * 主体定位状态机。
 */
public enum SubjectResolutionState {

    /** 已唯一定位，并且本次权限复核通过。 */
    RESOLVED,

    /** 存在一个或多个需要用户明确选择的安全候选。 */
    CANDIDATES,

    /** 在当前授权范围内未定位到候选。 */
    EMPTY,

    /** 来源系统拒绝或权限校验不可用。 */
    DENIED
}
