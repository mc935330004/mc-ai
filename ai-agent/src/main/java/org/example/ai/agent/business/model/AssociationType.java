package org.example.ai.agent.business.model;

/**
 * 业务主体之间的关联类型。
 */
public enum AssociationType {

    /** 直接关联。 */
    DIRECT,

    /** 项目、人员与期间共同形成的关联。 */
    PROJECT_PERSON_PERIOD,

    /** 不相关。 */
    UNRELATED
}
