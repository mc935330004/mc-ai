package org.example.ai.agent.sso;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册AI助手接口的统一访问边界。
 */
@Configuration
@RequiredArgsConstructor
public class AgentAccessWebConfiguration implements WebMvcConfigurer {

    private final AgentAccessInterceptor interceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        /*
         * Agent接口、知识库接口和旧版AI接口统一进入身份校验。
         *
         * 首次SSO Ticket交换仍由拦截器内部放行，
         * 不会提前要求用户已经建立Agent会话。
         */
        registry.addInterceptor(interceptor).addPathPatterns("/api/agent/**","/api/knowledge/**","/api/aiAgent/**");
    }
}