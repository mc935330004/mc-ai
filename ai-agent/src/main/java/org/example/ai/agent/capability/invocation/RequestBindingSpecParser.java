package org.example.ai.agent.capability.invocation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.example.ai.agent.capability.invocation.model.ParameterBindingSpec;
import org.example.ai.agent.capability.invocation.model.ParameterSourceType;
import org.example.ai.agent.capability.invocation.model.ParameterTargetLocation;
import org.example.ai.agent.capability.invocation.model.RequestBindingSpec;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 请求绑定配置解析器。
 *
 * 职责：
 * 1. 将 requestBindingJson 解析成强类型对象；
 * 2. 校验配置协议版本；
 * 3. 校验来源表达式；
 * 4. 校验 PATH、QUERY、BODY 目标配置；
 * 5. 校验 URL Path 占位符是否完整绑定；
 * 6. 阻止敏感信息硬编码。
 *
 * 注意：
 * 本类只校验配置，不负责真正构造 HTTP 请求。
 */
@Component
public class RequestBindingSpecParser {

    /**
     * 当前支持的请求绑定协议版本。
     */
    public static final String SUPPORTED_VERSION = "1.0";

    /**
     * 一个能力允许配置的最大参数数量。
     *
     * 限制数量可以防止异常配置造成过高的解析开销。
     */
    private static final int MAX_PARAMETER_COUNT = 100;

    /**
     * 当前执行器后续准备支持的 HTTP 方法。
     */
    private static final Set<String> SUPPORTED_HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    /**
     * 允许携带 JSON Body 的方法。
     *
     * 第一版明确禁止 GET 和 DELETE Body，减少跨系统兼容问题。
     */
    private static final Set<String> BODY_SUPPORTED_METHODS =
            Set.of("POST", "PUT", "PATCH");

    /**
     * 提取 URL 中的 {pathVariable}。
     */
    private static final Pattern PATH_PLACEHOLDER_PATTERN =
            Pattern.compile("\\{([^/{}]+)}");
    /**
     * HTTP Header 名称合法格式。
     *
     * 这里只允许 RFC Token 范围内的字符，
     * 防止换行符、冒号等非法字符进入请求头。
     */
    private static final Pattern HEADER_NAME_PATTERN =
            Pattern.compile("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$");

    /**
     * 禁止能力配置覆盖的系统 Header。
     *
     * 这些 Header 应当由 HTTP 客户端、网关或者协议栈维护。
     * 所有名称均使用小写，校验时统一转小写。
     */
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "host",
            "content-length",
            "connection",
            "transfer-encoding",
            "upgrade",
            "proxy-authorization",
            "proxy-authenticate",
            "set-cookie",
            "te",
            "trailer",
            "idempotency-key",
            "traceparent",
            "x-request-id"
    );

    /**
     * 只能从安全执行上下文读取的身份 Header。
     *
     * 大模型和工作流公开输入不得覆盖这些值，
     * 否则可能出现越权查询。
     */
    private static final Set<String> SECURE_CONTEXT_ONLY_HEADERS = Set.of( "authorization",
            "cookie",
            "x-user-id",
            "x-tenant-id",
            "x-organization-id",
            "x-dept-id"  );
    private final ObjectReader requestBindingReader;

    public RequestBindingSpecParser(ObjectMapper objectMapper) {
        /*
         * 使用 ObjectReader 而不是修改全局 ObjectMapper。
         *
         * FAIL_ON_UNKNOWN_PROPERTIES 可以防止字段拼写错误被静默忽略。
         */
        this.requestBindingReader = objectMapper
                .readerFor(RequestBindingSpec.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 解析并校验请求绑定配置。
     *
     * @param method          能力 HTTP 方法
     * @param capabilityUrl   能力相对 URL
     * @param bindingJson     请求绑定 JSON
     * @return 已通过校验的绑定配置
     */
    public RequestBindingSpec parse(
            String method,
            String capabilityUrl,
            String bindingJson) {

        String normalizedMethod = normalizeMethod(method);

        if (!StringUtils.hasText(capabilityUrl)) {
            throw invalid("能力URL不能为空");
        }

        if (!StringUtils.hasText(bindingJson)) {
            throw invalid("requestBindingJson不能为空");
        }

        RequestBindingSpec spec;

        try {
            spec = requestBindingReader.readValue(bindingJson);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(400,"请求绑定配置JSON解析失败：" + buildSafeJsonErrorMessage(exception) );
        }

        validateVersion(spec);
        validateParameters(
                normalizedMethod,
                capabilityUrl.trim(),
                spec
        );

        /*
         * 转换为不可增删的列表，避免解析完成后被调用方意外修改集合。
         * ParameterBindingSpec 本身暂时仍是可变 DTO，
         * 后续编译工作流时会生成真正的不可变运行快照。
         */
        spec.setParameters(List.copyOf(spec.getParameters()));

        return spec;
    }

    /**
     * 校验 HTTP 方法。
     */
    private String normalizeMethod(String method) {
        if (!StringUtils.hasText(method)) {
            throw invalid("能力HTTP方法不能为空");
        }

        String normalized = method.trim().toUpperCase(Locale.ROOT);

        if (!SUPPORTED_HTTP_METHODS.contains(normalized)) {
            throw invalid("暂不支持HTTP方法：" + normalized);
        }

        return normalized;
    }

    /**
     * 校验协议版本。
     */
    private void validateVersion(RequestBindingSpec spec) {
        if (spec == null) {
            throw invalid("请求绑定配置不能为空");
        }

        if (!SUPPORTED_VERSION.equals(spec.getVersion())) {
            throw invalid(
                    "不支持的请求绑定版本：" + spec.getVersion() +
                            "，当前仅支持：" + SUPPORTED_VERSION
            );
        }
    }

    /**
     * 校验全部参数绑定。
     */
    private void validateParameters(
            String method,
            String capabilityUrl,
            RequestBindingSpec spec) {

        List<ParameterBindingSpec> parameters = spec.getParameters();

        if (parameters == null) {
            throw invalid("parameters不能为空");
        }

        if (parameters.size() > MAX_PARAMETER_COUNT) {
            throw invalid(
                    "单个能力最多配置" +
                            MAX_PARAMETER_COUNT +
                            "个参数绑定"
            );
        }

        Set<String> uniqueTargets = new HashSet<>();
        Set<String> boundPathNames = new LinkedHashSet<>();

        for (int index = 0; index < parameters.size(); index++) {
            ParameterBindingSpec binding = parameters.get(index);

            if (binding == null) {
                throw invalid("parameters[" + index + "]不能为空");
            }

            validateSingleBinding(method, index, binding);
            validateTargetUniqueness(index, binding, uniqueTargets);

            if (binding.getTargetLocation()
                    == ParameterTargetLocation.PATH) {

                String pathName = binding.getTargetName().trim();

                if (!containsPathPlaceholder(capabilityUrl, pathName)) {
                    throw invalid(
                            "parameters[" + index + "]配置了PATH参数" +
                                    pathName +
                                    "，但能力URL中不存在{" +
                                    pathName +
                                    "}"
                    );
                }

                boundPathNames.add(pathName);
            }
        }

        validateAllPathPlaceholdersBound(
                capabilityUrl,
                boundPathNames
        );
    }

    /**
     * 校验单个参数。
     */
    private void validateSingleBinding(
            String method,
            int index,
            ParameterBindingSpec binding) {

        String prefix = "parameters[" + index + "]";

        if (binding.getSourceType() == null) {
            throw invalid(prefix + ".sourceType不能为空");
        }

        if (binding.getTargetLocation() == null) {
            throw invalid(prefix + ".targetLocation不能为空");
        }

        validateSource(prefix, binding);
        validateTarget(prefix, binding);

        if (binding.getTargetLocation()
                == ParameterTargetLocation.BODY
                && !BODY_SUPPORTED_METHODS.contains(method)) {

            throw invalid(
                    method + "请求不允许配置BODY参数：" + prefix
            );
        }
    }

    /**
     * 校验参数来源。
     */
    private void validateSource(
            String prefix,
            ParameterBindingSpec binding) {

        ParameterSourceType sourceType = binding.getSourceType();
        String expression = binding.getSourceExpression();

        if (sourceType == ParameterSourceType.FIXED) {
            if (binding.getFixedValue() == null
                    || binding.getFixedValue().isNull()) {

                throw invalid(
                        prefix +
                                "的sourceType=FIXED时必须配置fixedValue"
                );
            }

            if (StringUtils.hasText(expression)) {
                throw invalid(
                        prefix +
                                "的sourceType=FIXED时不能配置sourceExpression"
                );
            }

            if (binding.isSensitive()) {
                throw invalid(
                        prefix +
                                "不能使用FIXED保存敏感参数，" +
                                "敏感值必须来自SECURE_CONTEXT"
                );
            }

            return;
        }

        if (!StringUtils.hasText(expression)) {
            throw invalid(prefix + ".sourceExpression不能为空");
        }

        String normalizedExpression = expression.trim();

        switch (sourceType) {
            case INPUT -> requireExpressionPrefix(
                    prefix,
                    sourceType,
                    normalizedExpression,
                    "$input"
            );

            case VARIABLE -> requireExpressionPrefix(
                    prefix,
                    sourceType,
                    normalizedExpression,
                    "$vars"
            );

            case ITEM -> requireExpressionPrefix(
                    prefix,
                    sourceType,
                    normalizedExpression,
                    "$item"
            );

            case SECURE_CONTEXT -> {
                /*
                 * 不允许直接引用整个 $secure，
                 * 必须明确引用某个安全字段。
                 */
                if (!normalizedExpression.startsWith("$secure.")) {
                    throw invalid(
                            prefix +
                                    "的SECURE_CONTEXT表达式必须以" +
                                    "$secure.开头"
                    );
                }

                if (!binding.isSensitive()) {
                    throw invalid(
                            prefix +
                                    "使用SECURE_CONTEXT时必须设置" +
                                    "sensitive=true"
                    );
                }
            }

            case FIXED -> {
                // FIXED 已在前面单独处理，不会进入该分支。
            }
        }

        if (binding.getFixedValue() != null
                && !binding.getFixedValue().isNull()) {

            throw invalid(
                    prefix +
                            "只有sourceType=FIXED时才能配置fixedValue"
            );
        }
    }

    /**
     * 校验公开变量表达式根路径。
     */
    private void requireExpressionPrefix(
            String prefix,
            ParameterSourceType sourceType,
            String expression,
            String expectedRoot) {

        boolean rootReference = expression.equals(expectedRoot);
        boolean childReference =
                expression.startsWith(expectedRoot + ".");

        if (!rootReference && !childReference) {
            throw invalid(
                    prefix +
                            "的" +
                            sourceType +
                            "表达式必须以" +
                            expectedRoot +
                            "开头"
            );
        }
    }

    /**
     * 校验参数目标位置。
     */
    private void validateTarget(String prefix,ParameterBindingSpec binding) {

        ParameterTargetLocation location =  binding.getTargetLocation();
        //PATH、QUERY、HEADER 都通过 targetName 指定目标名称。
        if (location == ParameterTargetLocation.PATH
                || location == ParameterTargetLocation.QUERY
                || location == ParameterTargetLocation.HEADER) {

            if (!StringUtils.hasText(binding.getTargetName())) {
                throw invalid(
                        prefix +
                                ".targetName在" +
                                location +
                                "绑定中不能为空"
                );
            }

            if (StringUtils.hasText(binding.getTargetPath())) {
                throw invalid(
                        prefix +
                                "的" +
                                location +
                                "绑定不能配置targetPath"
                );
            }

            return;
        }

        if (location == ParameterTargetLocation.BODY) {
            if (!StringUtils.hasText(binding.getTargetPath())) {
                throw invalid(
                        prefix +
                                ".targetPath在BODY绑定中不能为空"
                );
            }

            String targetPath = binding.getTargetPath().trim();

            /*
             * 第一版使用受限路径语法：
             * $ 表示根对象；
             * $.xxx 表示对象内部字段。
             */
            if (!targetPath.equals("$") && !targetPath.startsWith("$.")) {
                throw invalid(
                        prefix +
                                ".targetPath必须为$或以$.开头"
                );
            }
            if (StringUtils.hasText(binding.getTargetName())) {
                throw invalid(
                        prefix +
                                "的BODY绑定不能同时配置targetName"
                );
            }
            return;
        }
        throw invalid(prefix + "配置了不支持的目标位置：" + location);
    }

    /**
     * 同一个请求目标只能被一个绑定写入。
     */
    private void validateTargetUniqueness(
            int index,
            ParameterBindingSpec binding,
            Set<String> uniqueTargets) {
        String target;

        if (binding.getTargetLocation()
                == ParameterTargetLocation.BODY) {

            target = binding.getTargetPath().trim();
        } else {
            target = binding.getTargetName().trim();
        }

        /*
         * HTTP Header 名称不区分大小写，
         * 因此统一转换成小写后再检查重复。
         */
        if (binding.getTargetLocation()
                == ParameterTargetLocation.HEADER) {

            target = target.toLowerCase(Locale.ROOT);
        }

        String uniqueKey =
                binding.getTargetLocation().name() +
                        ":" +
                        target;

        if (!uniqueTargets.add(uniqueKey)) {
            throw invalid(
                    "parameters[" + index + "]存在重复绑定：" +
                            uniqueKey
            );
        }
    }

    /**
     * 校验 URL 中的每个 Path 占位符都有对应绑定。
     */
    private void validateAllPathPlaceholdersBound(
            String capabilityUrl,
            Set<String> boundPathNames) {

        Matcher matcher =
                PATH_PLACEHOLDER_PATTERN.matcher(capabilityUrl);

        while (matcher.find()) {
            String placeholderName = matcher.group(1);

            if (!boundPathNames.contains(placeholderName)) {
                throw invalid(
                        "URL中的Path占位符" +
                                placeholderName +
                                "没有对应的PATH参数绑定"
                );
            }
        }
    }

    /**
     * 判断 URL 是否包含指定 Path 占位符。
     */
    private boolean containsPathPlaceholder(
            String capabilityUrl,
            String pathName) {

        return capabilityUrl.contains("{" + pathName + "}");
    }

    /**
     * 防止把完整原始 JSON 和可能的敏感值输出到错误消息。
     */
    private String buildSafeJsonErrorMessage(
            JsonProcessingException exception) {

        if (exception.getOriginalMessage() == null) {
            return "JSON格式不正确";
        }

        String message = exception.getOriginalMessage();

        /*
         * 限制错误长度，避免异常信息过大。
         */
        return message.length() <= 300
                ? message
                : message.substring(0, 300);
    }

    /**
     * 统一创建 400 业务异常。
     */
    private BusinessException invalid(String message) {
        return new BusinessException(
                400,
                "请求绑定配置无效：" + message
        );
    }

    /**
     * 校验 Header 目标。
     *
     * @param prefix  参数错误路径，例如 parameters[0]
     * @param binding 当前参数绑定
     */
    private void validateHeaderTarget( String prefix, ParameterBindingSpec binding) {

        String headerName = binding.getTargetName().trim();

        /*
         * 阻止换行符、冒号和其他非法字符进入 Header 名称，
         * 避免 Header Injection。
         */
        if (!HEADER_NAME_PATTERN.matcher(headerName).matches()) {
            throw invalid(
                    prefix +
                            "的HTTP Header名称不合法：" +
                            headerName
            );
        }

        String normalizedHeaderName =
                headerName.toLowerCase(Locale.ROOT);

        /*
         * 系统 Header 不允许被能力配置覆盖。
         */
        if (FORBIDDEN_HEADERS.contains(normalizedHeaderName)) {
            throw invalid(prefix +"禁止配置HTTP Header：" + headerName);
        }

        /*
         * 用户、租户和认证信息只能来自受信任的安全上下文。
         *
         * $input 和 $vars 都可能受到大模型或者工作流输入影响，
         * 因此不能用于设置身份 Header。
         */
        if (SECURE_CONTEXT_ONLY_HEADERS.contains(normalizedHeaderName)
                && binding.getSourceType()
                != ParameterSourceType.SECURE_CONTEXT) {

            throw invalid(
                    prefix +
                            "的安全身份Header只能来自SECURE_CONTEXT：" +
                            headerName
            );
        }
    }
}