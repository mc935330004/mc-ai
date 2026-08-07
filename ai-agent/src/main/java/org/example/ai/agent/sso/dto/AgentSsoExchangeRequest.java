package org.example.ai.agent.sso.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * Agent前端提交的一次性Ticket。
 */
@Getter
@Setter
public class AgentSsoExchangeRequest {

    @NotBlank(message = "SSO Ticket不能为空")
    private String ticket;
}