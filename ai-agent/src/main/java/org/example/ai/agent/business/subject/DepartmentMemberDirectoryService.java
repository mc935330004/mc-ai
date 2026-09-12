package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;

/**
 * 查询当前登录人在指定已复权部门下有权访问的成员。
 */
public interface DepartmentMemberDirectoryService {

    SubjectDirectoryPage search(DepartmentMemberDirectoryQuery query);
}
