package org.example.ai.agent.capability.invocation.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.invocation.RequestBindingSpecParser;
import org.example.ai.agent.capability.invocation.model.ParameterBindingSpec;
import org.example.ai.agent.capability.invocation.model.ParameterSourceType;
import org.example.ai.agent.capability.invocation.model.ParameterTargetLocation;
import org.example.ai.agent.capability.invocation.model.RequestBindingSpec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.lang.reflect.Array;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将能力发布配置编译成 HTTP 请求。
 */
@Component
@RequiredArgsConstructor
public class CapabilityHttpRequestBuilder {

    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 60000;

    private final ObjectMapper objectMapper;
    private final RequestBindingSpecParser bindingSpecParser;
    private final RestrictedExpressionResolver expressionResolver;
    private final CapabilityEndpointResolver endpointResolver;

    public CapabilityHttpRequest build(
            CapabilityDefinition capability,
            CapabilityInvocationContext context,
            String idempotencyKey) {

        if (capability == null) {
            throw invalid(
                    "CAPABILITY_DEFINITION_MISSING",
                    "能力定义不能为空"
            );
        }
        String endpoint = endpointResolver.resolve(capability);
        RequestBindingSpec spec = bindingSpecParser.parse(
                        capability.getMethod(),
                        capability.getUrl(),
                        capability.getRequestBindingJson());

        HttpMethod method;
        try {
            method = HttpMethod.valueOf(capability.getMethod().trim()
                            .toUpperCase());
        } catch (Exception exception) {
            throw invalid(
                    "CAPABILITY_HTTP_METHOD_INVALID",
                    "能力HTTP方法不合法：" +
                            capability.getMethod()
            );
        }

        validateRootBodyBinding(spec);

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(endpoint);

        Map<String, Object> pathVariables =new LinkedHashMap<>();

        HttpHeaders headers = new HttpHeaders();
        Map<String, Object> auditInput = new LinkedHashMap<>();

        JsonNode bodyNode = null;

        for (ParameterBindingSpec binding : spec.getParameters()) {

            Object value = resolveValue(binding, context);

            boolean missing = isMissing(value);

            if (missing && binding.getDefaultValue() != null && !binding.getDefaultValue().isNull()) {

                value = objectMapper.convertValue( binding.getDefaultValue(),Object.class);
                missing = isMissing(value);
            }

            if (missing && binding.isRequired()) {
                throw invalid("CAPABILITY_PARAMETER_REQUIRED", "能力必填参数缺失：" + safeTarget(binding));
            }

            if (missing && binding.isOmitIfNull()) {
                continue;
            }

            switch (binding.getTargetLocation()) {
                case PATH -> {
                    String scalar = requireScalar(
                            value,
                            binding
                    );

                    pathVariables.put(
                            binding.getTargetName(),
                            scalar
                    );

                    putAudit(
                            auditInput,
                            binding,
                            scalar
                    );
                }

                case QUERY -> {
                    addQueryParameter(
                            uriBuilder,
                            binding.getTargetName(),
                            value
                    );

                    putAudit(
                            auditInput,
                            binding,
                            value
                    );
                }

                case HEADER -> {
                    String scalar = requireScalar(
                            value,
                            binding
                    );

                    validateHeaderValue(scalar);

                    headers.set(
                            binding.getTargetName(),
                            scalar
                    );

                    putAudit(
                            auditInput,
                            binding,
                            scalar
                    );
                }

                case BODY -> {
                    bodyNode = writeBodyValue(
                            bodyNode,
                            binding.getTargetPath(),
                            value
                    );

                    putAudit(
                            auditInput,
                            binding,
                            value
                    );
                }
            }
        }

        /*
         * 兼容现有能力：
         * 如果绑定配置没有显式声明 Authorization，
         * 仍自动透传当前登录用户凭据。
         */
        String authorization = secureText(
                context,
                "authorization"
        );

        if (!headers.containsHeader( HttpHeaders.AUTHORIZATION ) && StringUtils.hasText(authorization)) {

            headers.set(
                    HttpHeaders.AUTHORIZATION,
                    authorization
            );

            auditInput.put(
                    "HEADER:Authorization",
                    "***"
            );
        }

        if (!headers.containsHeader(
                HttpHeaders.AUTHORIZATION
        )) {
            throw invalid(
                    "CAPABILITY_AUTHORIZATION_REQUIRED",
                    "调用业务系统缺少Authorization"
            );
        }

        /*
         * 幂等键只能由确认写操作运行时生成，
         * 不能来自能力配置或大模型输入。
         */
        if (StringUtils.hasText(idempotencyKey)) {
            if (headers.containsHeader("Idempotency-Key")) {
                throw invalid(
                        "IDEMPOTENCY_HEADER_CONFLICT",
                        "能力配置不能覆盖Idempotency-Key"
                );
            }

            headers.set(
                    "Idempotency-Key",
                    idempotencyKey.trim()
            );

            auditInput.put(
                    "HEADER:Idempotency-Key",
                    "***"
            );
        }

        if (bodyNode != null) {
            String contentType =
                    StringUtils.hasText(
                            capability.getRequestContentType()
                    )
                            ? capability.getRequestContentType()
                            : MediaType.APPLICATION_JSON_VALUE;

            try {
                headers.setContentType(
                        MediaType.parseMediaType(contentType)
                );
            } catch (Exception exception) {
                throw invalid(
                        "CAPABILITY_CONTENT_TYPE_INVALID",
                        "能力请求Content-Type不合法"
                );
            }
        }

        headers.setAccept(
                List.of(MediaType.APPLICATION_JSON)
        );

        URI uri;

        try {
            uri = uriBuilder
                    .buildAndExpand(pathVariables)
                    .encode()
                    .toUri();
        } catch (Exception exception) {
            throw invalid(
                    "CAPABILITY_URI_BUILD_FAILED",
                    "业务接口地址构建失败"
            );
        }

        int timeoutMs = resolveTimeout(
                capability.getTimeoutMs()
        );

        Object body = bodyNode == null
                ? null
                : objectMapper.convertValue(
                        bodyNode,
                        Object.class
                );

        return CapabilityHttpRequest.builder()
                .method(method)
                .uri(uri)
                .headers(headers)
                .body(body)
                .timeoutMs(timeoutMs)
                .auditInput(
                        Collections.unmodifiableMap(
                                auditInput
                        )
                )
                .build();
    }

    private Object resolveValue( ParameterBindingSpec binding, CapabilityInvocationContext context) {

        if (binding.getSourceType()== ParameterSourceType.FIXED) {
            return binding.getFixedValue() == null || binding.getFixedValue().isNull()
                    ? null: objectMapper.convertValue( binding.getFixedValue(),Object.class);
        }
        return expressionResolver.resolve(binding.getSourceExpression(),context);
    }

    private void validateRootBodyBinding(
            RequestBindingSpec spec) {

        List<ParameterBindingSpec> bodyBindings =
                spec.getParameters()
                        .stream()
                        .filter(item ->
                                item.getTargetLocation()
                                        == ParameterTargetLocation.BODY
                        )
                        .toList();

        boolean containsRoot =
                bodyBindings.stream()
                        .anyMatch(item ->
                                "$".equals(
                                        item.getTargetPath()
                                )
                        );

        if (containsRoot && bodyBindings.size() > 1) {
            throw invalid(
                    "CAPABILITY_BODY_ROOT_CONFLICT",
                    "BODY根路径$不能与其他BODY绑定同时使用"
            );
        }
    }

    private JsonNode writeBodyValue(
            JsonNode currentBody,
            String targetPath,
            Object value) {

        JsonNode valueNode = value == null
                ? NullNode.getInstance()
                : objectMapper.valueToTree(value);

        if ("$".equals(targetPath)) {
            return valueNode;
        }

        if (!targetPath.startsWith("$.")) {
            throw invalid(
                    "CAPABILITY_BODY_PATH_INVALID",
                    "BODY目标路径不合法"
            );
        }

        ObjectNode root;

        if (currentBody == null) {
            root = objectMapper.createObjectNode();
        } else if (currentBody.isObject()) {
            root = (ObjectNode) currentBody;
        } else {
            throw invalid(
                    "CAPABILITY_BODY_STRUCTURE_CONFLICT",
                    "BODY根节点不是对象"
            );
        }

        String[] parts =
                targetPath.substring(2).split("\\.");

        ObjectNode current = root;

        for (int index = 0;
             index < parts.length - 1;
             index++) {

            String part = parts[index];
            JsonNode existing = current.get(part);

            if (existing == null || existing.isNull()) {
                ObjectNode child =
                        objectMapper.createObjectNode();

                current.set(part, child);
                current = child;
                continue;
            }

            if (!existing.isObject()) {
                throw invalid(
                        "CAPABILITY_BODY_STRUCTURE_CONFLICT",
                        "BODY目标路径存在结构冲突：" +
                                targetPath
                );
            }

            current = (ObjectNode) existing;
        }

        current.set(
                parts[parts.length - 1],
                valueNode
        );

        return root;
    }

    private void addQueryParameter(
            UriComponentsBuilder builder,
            String name,
            Object value) {

        if (value == null) {
            builder.queryParam(name, "");
            return;
        }

        if (value instanceof Collection<?> collection) {
            builder.queryParam(
                    name,
                    collection.toArray()
            );
            return;
        }

        if (value.getClass().isArray()) {
            List<Object> values = new ArrayList<>();

            for (int index = 0;
                 index < Array.getLength(value);
                 index++) {
                values.add(Array.get(value, index));
            }

            builder.queryParam(
                    name,
                    values.toArray()
            );
            return;
        }

        if (value instanceof JsonNode node
                && node.isArray()) {

            List<Object> values = new ArrayList<>();

            node.forEach(item ->
                    values.add(
                            objectMapper.convertValue(
                                    item,
                                    Object.class
                            )
                    )
            );

            builder.queryParam(
                    name,
                    values.toArray()
            );

            return;
        }

        builder.queryParam(
                name,
                requireScalarValue(value, name)
        );
    }

    private String requireScalar(
            Object value,
            ParameterBindingSpec binding) {

        if (value == null) {
            throw invalid(
                    "CAPABILITY_PARAMETER_REQUIRED",
                    "PATH或HEADER参数不能为空：" +
                            safeTarget(binding)
            );
        }

        return requireScalarValue(
                value,
                safeTarget(binding)
        );
    }

    private String requireScalarValue(
            Object value,
            String target) {

        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>) {

            return String.valueOf(value);
        }

        if (value instanceof JsonNode node
                && node.isValueNode()) {
            return node.asText();
        }

        throw invalid(
                "CAPABILITY_PARAMETER_TYPE_INVALID",
                "PATH、QUERY或HEADER参数必须是标量：" +
                        target
        );
    }

    private void validateHeaderValue(String value) {
        if (value.contains("\r")
                || value.contains("\n")) {
            throw invalid(
                    "CAPABILITY_HEADER_VALUE_INVALID",
                    "HTTP Header值不能包含换行符"
            );
        }
    }

    private boolean isMissing(Object value) {
        if (value == null) {
            return true;
        }

        if (value instanceof String text) {
            return !StringUtils.hasText(text);
        }

        if (value instanceof Collection<?> collection) {
            return collection.isEmpty();
        }

        return value.getClass().isArray()
                && Array.getLength(value) == 0;
    }

    private String secureText(
            CapabilityInvocationContext context,
            String name) {

        if (context == null
                || context.getSecure() == null) {
            return null;
        }

        Object value = context.getSecure().get(name);

        return value == null
                ? null
                : String.valueOf(value);
    }

    private void putAudit(
            Map<String, Object> audit,
            ParameterBindingSpec binding,
            Object value) {

        String key =
                binding.getTargetLocation().name()
                        + ":"
                        + safeTarget(binding);

        audit.put(
                key,
                binding.isSensitive()
                        ? "***"
                        : value
        );
    }

    private String safeTarget(
            ParameterBindingSpec binding) {

        return binding.getTargetLocation()
                == ParameterTargetLocation.BODY
                ? binding.getTargetPath()
                : binding.getTargetName();
    }

    private int resolveTimeout(Integer configured) {
        int value = configured == null
                ? DEFAULT_TIMEOUT_MS
                : configured;

        if (value < MIN_TIMEOUT_MS
                || value > MAX_TIMEOUT_MS) {
            throw invalid(
                    "CAPABILITY_TIMEOUT_INVALID",
                    "能力超时时间必须在100到60000毫秒之间"
            );
        }

        return value;
    }

    private CapabilityInvocationException invalid(
            String code,
            String message) {

        return new CapabilityInvocationException(
                code,
                message
        );
    }
}