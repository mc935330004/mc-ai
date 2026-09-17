package org.example.ai.agent.business.subject.model;

/**
 * 后端允许的确定性主体目录查询方式。
 * 模型只能提供搜索提示，不能直接决定查询模式。
 */
public enum SubjectSearchMode {

    /**
     * 当前用户负责或参与的项目。
     */
    MY_PROJECTS,

    /**
     * 当前用户明确可查看的全部项目。
     */
    VIEWABLE_PROJECTS,

    PROJECT_CODE,
    PROJECT_NAME,
    PROJECT_MANAGER,
    CURRENT_PERSON,
    EMPLOYEE_NO,
    PERSON_NAME,
    DEPARTMENT_NAME,
    SELECTED_SUBJECT
}