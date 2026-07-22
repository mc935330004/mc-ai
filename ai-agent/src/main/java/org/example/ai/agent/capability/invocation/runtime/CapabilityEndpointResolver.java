package org.example.ai.agent.capability.invocation.runtime;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.BusinessSystem;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.BusinessSystemService;
import org.example.ai.agent.common.config.BusinessApiProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 业务能力接口地址解析器。
 *
 * 限制绝对地址必须与已配置业务系统同源，
 * 防止能力配置被用于 SSRF。
 */
@Component
@RequiredArgsConstructor
public class CapabilityEndpointResolver {

    private final BusinessSystemService businessSystemService;
    private final BusinessApiProperties businessApiProperties;

    public String resolve(CapabilityDefinition capability) {
        if (capability == null
                || !StringUtils.hasText(capability.getUrl())) {
            throw invalid(
                    "CAPABILITY_URL_MISSING",
                    "能力接口地址不能为空"
            );
        }

        String baseUrl = resolveBaseUrl(capability);
        String capabilityUrl = capability.getUrl().trim();

        validateHttpUrl(baseUrl);

        if (isAbsoluteUrl(capabilityUrl)) {
            validateSameOrigin(baseUrl, capabilityUrl);
            return capabilityUrl;
        }

        return joinUrl(baseUrl, capabilityUrl);
    }

    private String resolveBaseUrl(
            CapabilityDefinition capability) {

        if (StringUtils.hasText(
                capability.getSystemCode()
        )) {
            BusinessSystem system =
                    businessSystemService.getEnabledByCode(
                            capability.getSystemCode()
                    );

            if (system == null
                    || !StringUtils.hasText(
                    system.getBaseUrl()
            )) {
                throw invalid(
                        "BUSINESS_SYSTEM_NOT_FOUND",
                        "能力所属业务系统不存在或未启用：" +
                                capability.getSystemCode()
                );
            }

            return system.getBaseUrl().trim();
        }

        if (!StringUtils.hasText( businessApiProperties.getBaseUrl())) {
            throw invalid(
                    "BUSINESS_SYSTEM_BASE_URL_MISSING",
                    "业务系统基础地址未配置"
            );
        }

        return businessApiProperties.getBaseUrl().trim();
    }

    private void validateSameOrigin(
            String baseUrl,
            String capabilityUrl) {

        URI base = parseTemplateUri(baseUrl);
        URI target = parseTemplateUri(capabilityUrl);

        boolean sameOrigin =
                normalize(base.getScheme())
                        .equals(normalize(target.getScheme()))
                        && normalize(base.getHost())
                        .equals(normalize(target.getHost()))
                        && effectivePort(base)
                        == effectivePort(target);

        if (!sameOrigin) {
            throw invalid(
                    "CAPABILITY_URL_ORIGIN_NOT_ALLOWED",
                    "能力绝对地址不属于已配置业务系统"
            );
        }
    }

    private void validateHttpUrl(String url) {
        URI uri = parseTemplateUri(url);

        if (!List.of("http", "https").contains( normalize(uri.getScheme()))) {
            throw invalid(
                    "CAPABILITY_URL_SCHEME_NOT_ALLOWED",
                    "业务系统地址只允许HTTP或HTTPS"
            );
        }

        if (!StringUtils.hasText(uri.getHost())) {
            throw invalid(
                    "CAPABILITY_URL_INVALID",
                    "业务系统地址缺少Host"
            );
        }

        if (StringUtils.hasText(uri.getUserInfo())) {
            throw invalid(
                    "CAPABILITY_URL_USERINFO_NOT_ALLOWED",
                    "业务系统地址禁止包含UserInfo"
            );
        }
    }

    private URI parseTemplateUri(String url) {
        try {
            /*
             * URI 不接受 {projectId}，
             * 校验地址时先使用安全占位文本替换。
             */
            return URI.create(
                    url.replaceAll(
                            "\\{[^/{}]+}",
                            "template-value"
                    )
            );
        } catch (Exception exception) {
            throw invalid(
                    "CAPABILITY_URL_INVALID",
                    "能力接口地址格式不正确"
            );
        }
    }

    private boolean isAbsoluteUrl(String value) {
        String normalized =
                value.toLowerCase(Locale.ROOT);

        return normalized.startsWith("http://")
                || normalized.startsWith("https://");
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }

        return "https".equalsIgnoreCase(
                uri.getScheme()
        ) ? 443 : 80;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT);
    }

    private String joinUrl(
            String baseUrl,
            String path) {

        if (baseUrl.endsWith("/")
                && path.startsWith("/")) {
            return baseUrl.substring(
                    0,
                    baseUrl.length() - 1
            ) + path;
        }

        if (!baseUrl.endsWith("/")
                && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }

        return baseUrl + path;
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