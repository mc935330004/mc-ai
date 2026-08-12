package org.example.ai.agent.modules.knowledgebase.security;

/**
 * 知识库查询使用的可信登录身份。
 *
 * 该对象只能由服务端认证上下文创建，
 * 不能从前端请求参数反序列化。
 */
public record KnowledgeAccessPrincipal(
        String userId,
        Long tenantId,
        Long deptId) {
}