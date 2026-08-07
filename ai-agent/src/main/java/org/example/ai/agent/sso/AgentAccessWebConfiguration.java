package org.example.ai.agent.sso;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册Agent接口访问边界。
 */
@Configuration
@RequiredArgsConstructor
public class AgentAccessWebConfiguration implements WebMvcConfigurer {

    private final AgentAccessInterceptor interceptor;

    @Override
    public void addInterceptors( InterceptorRegistry registry) {

        /*
         * 中文注释：
         * auth接口同样需要进行Origin校验，
         * 但AgentAccessInterceptor不会要求首次Ticket交换已有会话。
         */
        registry.addInterceptor(interceptor).addPathPatterns("/api/agent/**");
    }
}