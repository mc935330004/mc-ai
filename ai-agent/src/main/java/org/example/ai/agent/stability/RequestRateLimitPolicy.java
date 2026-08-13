package org.example.ai.agent.stability;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

/**
 * 识别需要较严格限流的高成本接口。
 */
@Component
public class RequestRateLimitPolicy {

    private static final AntPathMatcher PATH_MATCHER =
            new AntPathMatcher();

    /**
     * 判断请求是否会占用模型、文件或工作流资源。
     */
    public boolean isExpensive(String method, String path) {
        if (HttpMethod.POST.matches(method)
                && "/api/agent/chat/stream".equals(path)) {
            return true;
        }
        if (HttpMethod.POST.matches(method)
                && ("/api/knowledge/documents/query".equals(path)
                || "/api/knowledge/documents/query/stream".equals(path))) {
            return true;
        }
        if (HttpMethod.POST.matches(method)
                && (PATH_MATCHER.match(
                "/api/knowledge/documents/upload",
                path
        ) || PATH_MATCHER.match(
                "/api/knowledge/documents/textUpload",
                path
        ) || PATH_MATCHER.match(
                "/api/knowledge/versions/*/versions/*/revectorize",
                path
        ))) {
            return true;
        }
        if (HttpMethod.POST.matches(method)
                && PATH_MATCHER.match(
                "/api/agent/capabilities/*/test",
                path
        )) {
            return true;
        }
        if (HttpMethod.POST.matches(method)
                && PATH_MATCHER.match(
                "/api/agent/capabilities/*/sample",
                path
        )) {
            return true;
        }
        if (HttpMethod.POST.matches(method)
                && PATH_MATCHER.match(
                "/api/agent/capabilities/*/fields/**/upload",
                path
        )) {
            return true;
        }
        if (HttpMethod.POST.matches(method)
                && PATH_MATCHER.match(
                "/api/agent/workflows/*/debug",
                path
        )) {
            return true;
        }
        if (HttpMethod.POST.matches(method)
                && ("/api/agent/capabilityOpenapi/preview".equals(path)
                || "/api/agent/capabilityOpenapi/import".equals(path)
                || "/api/agent/capabilityOpenapi/sync-preview".equals(path)
                || "/api/agent/capabilityOpenapi/sync".equals(path)
                || "/api/agent/dictionaries/semantic/suggest".equals(path)
                || "/api/agent/capabilities/vector-index/rebuild".equals(path)
                || "/api/agent/route-evaluation/run".equals(path))) {
            return true;
        }
        return HttpMethod.POST.matches(method)
                && PATH_MATCHER.match(
                "/api/agent/admin/models/*/test",
                path
        );
    }
}
