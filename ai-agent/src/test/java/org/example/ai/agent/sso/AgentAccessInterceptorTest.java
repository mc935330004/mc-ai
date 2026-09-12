package org.example.ai.agent.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.ai.agent.security.CurrentUserProvider;
import org.example.ai.agent.stability.RedisRequestRateLimiter;
import org.example.ai.agent.stability.RequestRateLimitPolicy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentAccessInterceptorTest {

    @ParameterizedTest
    @CsvSource({
            "GET,/api/agent/report-tasks/task-1",
            "GET,/api/agent/report-tasks/task-1/download",
            "POST,/api/agent/report-tasks/task-1/cancel"
    })
    void reportTaskOwnerPathsShouldNotRequireAdminPermission(String method, String path) {
        CurrentUserProvider currentUser = mock(CurrentUserProvider.class);
        AgentSsoProperties properties = new AgentSsoProperties();
        properties.setEnabled(true);
        RedisRequestRateLimiter rateLimiter = mock(RedisRequestRateLimiter.class);
        RequestRateLimitPolicy rateLimitPolicy = mock(RequestRateLimitPolicy.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);

        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getContextPath()).thenReturn("");
        when(currentUser.getRequiredUserId()).thenReturn("user-1");
        when(rateLimiter.tryAcquire("user-1", false)).thenReturn(true);
        when(rateLimitPolicy.isExpensive(method, path)).thenReturn(false);

        AgentAccessInterceptor interceptor = new AgentAccessInterceptor(
                currentUser,
                properties,
                rateLimiter,
                rateLimitPolicy
        );

        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(currentUser, never()).requirePermission(AgentSsoConstants.ADMIN_PERMISSION);
    }
}
