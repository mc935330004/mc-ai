package org.example.ai.agent.capability.invocation.runtime;

import org.springframework.web.client.RestClient;

public interface CapabilityRestClientFactory {

    RestClient create(int timeoutMs);
}