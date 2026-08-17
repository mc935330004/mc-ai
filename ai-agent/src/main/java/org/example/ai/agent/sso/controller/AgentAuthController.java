package org.example.ai.agent.sso.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.security.CurrentUserProvider;
import org.example.ai.agent.sso.*;
import org.example.ai.agent.sso.dto.AgentSsoExchangeRequest;
import org.example.ai.agent.sso.dto.PmSsoIdentity;
import org.example.ai.agent.sso.model.AgentSession;
import org.example.ai.agent.sso.vo.AgentCurrentUserVO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * Agent单点登录接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/auth")
public class AgentAuthController {

    private final PmSsoClient pmSsoClient;
    private final AgentSessionService sessionService;
    private final AgentSsoProperties properties;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 使用一次性Ticket建立Agent会话。
     */
    @PostMapping("/sso/exchange")
    public Result<AgentCurrentUserVO> exchange( @Valid @RequestBody AgentSsoExchangeRequest request,
            HttpServletResponse response) {

        PmSsoIdentity identity =pmSsoClient.exchange(request.getTicket());

        String sessionId =sessionService.createSession(identity);

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                buildSessionCookie(sessionId).toString()
        );
        return Result.success(
                toVO(
                        identity.getUserId(),
                        identity.getUsername(),
                        identity.getTarget(),
                        identity.hasAgentAdminPermission()
                )
        );
    }

    /**
     * 获取当前登录用户。
     *
     * 先请求PM /user/info验证Token仍然有效，
     * 再返回Agent会话信息。
     */
    @GetMapping("/me")
    public Result<AgentCurrentUserVO> currentUser() {
        currentUserProvider.getRequiredUserId();

        AgentSession session = sessionService.getRequiredSession();

        boolean agentAdmin = currentUserProvider.hasPermission(AgentSsoConstants.ADMIN_PERMISSION);

        return Result.success(
                toVO(
                        session.getPmUserId(),
                        session.getUsername(),
                        session.getTarget(),
                        agentAdmin
                )
        );
    }

    /**
     * 销毁Agent会话。
     */
    @PostMapping("/logout")
    public Result<Void> logout(
            HttpServletResponse response) {

        sessionService.deleteCurrentSession();

        response.addHeader(
                HttpHeaders.SET_COOKIE,
                buildDeleteCookie().toString()
        );

        return Result.success();
    }

    private ResponseCookie buildSessionCookie(
            String sessionId) {

        return ResponseCookie
                .from(
                        properties.getSessionCookieName(),
                        sessionId
                )
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite(properties.getCookieSameSite())
                .path("/")
                .maxAge(
                        Duration.ofSeconds(
                                sessionService
                                        .getSessionTtlSeconds()
                        )
                )
                .build();
    }

    private ResponseCookie buildDeleteCookie() {
        return ResponseCookie
                .from(
                        properties.getSessionCookieName(),
                        ""
                )
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite(properties.getCookieSameSite())
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    private AgentCurrentUserVO toVO(
            Long pmUserId,
            String username,
            String target,
            boolean agentAdmin) {

        return AgentCurrentUserVO.builder()
                .pmUserId(pmUserId)
                .username(username)
                .target(target)
                .agentAdmin(agentAdmin)
                .build();
    }
}