package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;

/**
 * 当前登录人有权查询的部门目录。
 */
public interface DepartmentDirectoryService {

    SubjectDirectoryPage search(SubjectDirectoryQuery query);
}
