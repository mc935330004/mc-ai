package org.example.ai.agent.stability;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 高成本接口限流策略测试。
 */
class RequestRateLimitPolicyTest {

    private final RequestRateLimitPolicy policy =
            new RequestRateLimitPolicy();

    @Test
    void shouldTreatChatAndKnowledgeQueryAsExpensive() {
        assertTrue(policy.isExpensive(
                "POST",
                "/api/agent/chat/stream"
        ));
        assertTrue(policy.isExpensive(
                "POST",
                "/api/knowledge/documents/query"
        ));
        assertTrue(policy.isExpensive(
                "POST",
                "/api/knowledge/documents/query/stream"
        ));
    }

    @Test
    void shouldTreatModelTestAndWorkflowDebugAsExpensive() {
        assertTrue(policy.isExpensive(
                "POST",
                "/api/agent/admin/models/deepseek/test"
        ));
        assertTrue(policy.isExpensive(
                "POST",
                "/api/agent/workflows/12/debug"
        ));
        assertTrue(policy.isExpensive(
                "POST",
                "/api/agent/workflows/12/draft-report-preview"
        ));
        assertTrue(policy.isExpensive(
                "POST",
                "/api/agent/capabilityOpenapi/sync-preview"
        ));
    }

    @Test
    void shouldTreatVectorAndEvaluationTasksAsExpensive() {
        assertTrue(policy.isExpensive(
                "POST",
                "/api/knowledge/documents/textUpload"
        ));
        assertTrue(policy.isExpensive(
                "POST",
                "/api/knowledge/versions/12/versions/8/revectorize"
        ));
        assertTrue(policy.isExpensive(
                "POST",
                "/api/agent/capabilities/vector-index/rebuild"
        ));
        assertTrue(policy.isExpensive(
                "POST",
                "/api/agent/route-evaluation/run"
        ));
    }

    @Test
    void shouldKeepReadOnlyListsInDefaultBucket() {
        assertFalse(policy.isExpensive(
                "GET",
                "/api/agent/chat/sessions"
        ));
    }
}
