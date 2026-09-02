package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.subject.model.SubjectDirectoryPage;

/**
 * 来源系统根据当前登录人角色返回的授权人员目录。
 */
public interface AuthorizedPersonDirectoryService {

    SubjectDirectoryPage search(SubjectDirectoryQuery query);
}
