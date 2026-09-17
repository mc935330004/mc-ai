package org.example.ai.agent.common.model;

/**
 * 项目列表查询范围。
 */
public enum ProjectListScope {

    /**
     * 当前用户负责或参与的项目。
     */
    MY_PROJECTS,

    /**
     * 当前用户所有明确可查看的项目。
     * 只有用户明确提出“可查看”时才能使用。
     */
    VIEWABLE_PROJECTS
}