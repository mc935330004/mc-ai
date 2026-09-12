package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;

/**
 * 当前登录人有权查看的项目目录。
 */
public interface ProjectDirectoryService {

    SubjectDirectoryPage search(SubjectDirectoryQuery query);
}
