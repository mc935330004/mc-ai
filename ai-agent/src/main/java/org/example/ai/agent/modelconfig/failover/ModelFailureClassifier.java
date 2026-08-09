package org.example.ai.agent.modelconfig.failover;

import com.openai.errors.BadRequestException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import org.example.ai.agent.common.enums.ModelFailureCategory;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * 模型异常分类器。
 *
 * 优先使用异常类型、HTTP状态和结构化错误码，
 * 不使用模糊错误文本决定是否切换。
 */
@Component
public class ModelFailureClassifier {

    private static final Set<String> QUOTA_CODES =
            Set.of(
                    "insufficient_quota",
                    "quota_exceeded",
                    "billing_hard_limit_reached"
            );

    private static final Set<String> SAFETY_CODES =
            Set.of(
                    "content_filter",
                    "content_policy_violation",
                    "safety_violation"
            );

    public ModelFailureCategory classify(
            Throwable throwable) {

        if (hasCause(
                throwable,
                CancellationException.class)) {
            return ModelFailureCategory.CANCELLED;
        }

        if (hasCause(
                throwable,
                BusinessException.class)) {
            return ModelFailureCategory.BUSINESS_ERROR;
        }

        OpenAIServiceException serviceException =
                findCause(
                        throwable,
                        OpenAIServiceException.class
                );

        if (serviceException != null) {
            return classifyServiceException(
                    serviceException
            );
        }

        if (isTimeout(throwable)) {
            return ModelFailureCategory.TIMEOUT;
        }

        if (hasCause(throwable, OpenAIIoException.class)
                || hasCause(
                throwable,
                OpenAIRetryableException.class)
                || hasCause(
                throwable,
                ConnectException.class)
                || hasCause(
                throwable,
                UnknownHostException.class)) {
            return ModelFailureCategory.CONNECTION_ERROR;
        }

        return ModelFailureCategory.UNKNOWN;
    }

    private ModelFailureCategory classifyServiceException(
            OpenAIServiceException exception) {

        String code = normalize(
                exception.code().orElse(null)
        );

        String type = normalize(
                exception.type().orElse(null)
        );

        if (SAFETY_CODES.contains(code)
                || SAFETY_CODES.contains(type)) {
            return ModelFailureCategory.SAFETY_REJECTION;
        }

        if (exception instanceof UnauthorizedException
                || exception instanceof PermissionDeniedException) {
            return ModelFailureCategory.AUTHENTICATION_ERROR;
        }

        if (exception instanceof RateLimitException
                || exception.statusCode() == 429) {

            if (QUOTA_CODES.contains(code)
                    || QUOTA_CODES.contains(type)) {
                return ModelFailureCategory.QUOTA_EXHAUSTED;
            }

            return ModelFailureCategory.RATE_LIMIT;
        }

        if (exception.statusCode() >= 500
                && exception.statusCode() <= 599) {
            return ModelFailureCategory.PROVIDER_5XX;
        }

        if (exception instanceof BadRequestException
                || exception.statusCode() == 400
                || exception.statusCode() == 404
                || exception.statusCode() == 422) {
            return ModelFailureCategory.BAD_REQUEST;
        }

        return ModelFailureCategory.UNKNOWN;
    }

    private boolean isTimeout(Throwable throwable) {
        return hasCause(
                throwable,
                SocketTimeoutException.class)
                || hasCause(
                throwable,
                HttpTimeoutException.class)
                || hasCause(
                throwable,
                TimeoutException.class);
    }

    private boolean hasCause(
            Throwable throwable,
            Class<? extends Throwable> type) {

        return findCause(throwable, type) != null;
    }

    private <T extends Throwable> T findCause(
            Throwable throwable,
            Class<T> type) {

        Throwable current = throwable;

        while (current != null) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }

            current = current.getCause();
        }

        return null;
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim()
                .toLowerCase(Locale.ROOT);
    }
}