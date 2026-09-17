package org.example.ai.agent.common.model;

/**
 * 当前用户与项目的授权关系。
 */
public enum ProjectRelationship {

    /**
     * 当前用户负责该项目。
     */
    RESPONSIBLE,

    /**
     * 当前用户参与该项目。
     */
    PARTICIPATING,

    /**
     * 当前用户只有查看权限。
     */
    VIEWABLE
}