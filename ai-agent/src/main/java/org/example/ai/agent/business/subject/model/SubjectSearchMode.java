package org.example.ai.agent.business.subject.model;

/**
 * 后端允许的确定性主体目录查询方式，模型只能提供提示字段，不能直接决定该值。
 */
public enum SubjectSearchMode {
    MY_PROJECTS,
    PROJECT_CODE,
    PROJECT_NAME,
    PROJECT_MANAGER,
    CURRENT_PERSON,
    EMPLOYEE_NO,
    PERSON_NAME,
    DEPARTMENT_NAME,
    SELECTED_SUBJECT
}
