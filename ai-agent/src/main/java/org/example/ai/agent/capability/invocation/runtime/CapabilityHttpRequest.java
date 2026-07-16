package org.example.ai.agent.capability.invocation.runtime;

import lombok.Builder;
import lombok.Getter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.util.Map;

/**
 * 编译完成的业务能力 HTTP 请求。
 *
 * toString 不输出 Header、Body 和 Query，
 * 防止 Authorization 或敏感查询参数进入日志。
 */
@Getter
@Builder
public class CapabilityHttpRequest {

    private HttpMethod method;

    private URI uri;

    private HttpHeaders headers;

    private Object body;

    private int timeoutMs;

    /**
     * 已脱敏、允许保存到运行轨迹的调用输入。
     */
    private Map<String, Object> auditInput;

    @Override
    public String toString() {
        String safeUri = uri == null
                ? null
                : uri.getScheme()
                + "://"
                + uri.getAuthority()
                + uri.getPath();

        return "CapabilityHttpRequest{" +
                "method=" + method +
                ", uri=" + safeUri +
                ", timeoutMs=" + timeoutMs +
                ", auditInput=" + auditInput +
                '}';
    }
}