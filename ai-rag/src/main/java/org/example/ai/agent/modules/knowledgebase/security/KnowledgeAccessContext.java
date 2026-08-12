package org.example.ai.agent.modules.knowledgebase.security;

/**
 * 为知识库模块提供当前登录用户的访问上下文。
 *
 * 接口放在ai-rag模块，具体身份实现由ai-agent模块提供，
 * 避免ai-rag反向依赖ai-agent。
 */
public interface KnowledgeAccessContext {

    /**
     * 获取经过服务端认证的知识库访问身份。
     */
    KnowledgeAccessPrincipal getRequiredPrincipal();

    /**
     * 获取当前请求中已经建立的知识库身份。
     *
     * 普通业务聊天兼容仅携带Authorization的旧调用方，
     * 没有Agent会话时返回null；真正进入知识库查询时仍会强制校验。
     */
    KnowledgeAccessPrincipal getCurrentPrincipal();
}
