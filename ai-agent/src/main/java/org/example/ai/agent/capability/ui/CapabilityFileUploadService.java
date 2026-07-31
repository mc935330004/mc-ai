package org.example.ai.agent.capability.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.invocation.runtime.CapabilityHttpInvoker;
import org.example.ai.agent.capability.invocation.runtime.CapabilityHttpRequest;
import org.example.ai.agent.capability.invocation.runtime.CapabilityHttpRequestBuilder;
import org.example.ai.agent.capability.invocation.runtime.CapabilityInvocationContext;
import org.example.ai.agent.capability.invocation.runtime.CapabilityInvocationContextFactory;
import org.example.ai.agent.capability.invocation.runtime.CapabilityResponseInterpreter;
import org.example.ai.agent.capability.invocation.runtime.ResponseInterpretationResult;
import org.example.ai.agent.capability.invocation.runtime.SimpleJsonPathReader;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.example.ai.agent.capability.vo.CapabilityFileUploadVO;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.plan.PlanStep;
import org.example.ai.agent.plan.StepType;
import org.example.ai.agent.security.PmCapabilityPermissionVerifier;
import org.example.ai.agent.tool.ToolExecutionContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * WRITE动态表单通用文件上传服务。
 *
 * 核心安全规则：
 * 1. 上传能力只能从WRITE字段Schema中读取；
 * 2. 前端不能临时指定上传能力编码；
 * 3. 上传能力必须是已发布、已启用的WRITE能力；
 * 4. 上传前重新校验当前PM用户权限；
 * 5. 继续复用现有Endpoint、Authorization、请求绑定和响应绑定。
 */
@Service
@RequiredArgsConstructor
public class CapabilityFileUploadService {

    private final CapabilityDefinitionService capabilityDefinitionService;

    private final CapabilityUiSchemaParser uiSchemaParser;

    private final CapabilityInvocationContextFactory invocationContextFactory;

    private final CapabilityHttpRequestBuilder httpRequestBuilder;

    private final CapabilityHttpInvoker httpInvoker;

    private final CapabilityResponseInterpreter responseInterpreter;

    private final PmCapabilityPermissionVerifier permissionVerifier;

    private final SimpleJsonPathReader jsonPathReader;

    private final ObjectMapper objectMapper;

    /**
     * 上传动态表单字段文件。
     */
    public CapabilityFileUploadVO upload(
            String writeCapabilityCode,
            String fieldPath,
            MultipartFile file,
            Map<String, Object> form,
            String userId,
            String authorization) {

        requireText(writeCapabilityCode, "WRITE能力编码不能为空");
        requireText(fieldPath, "上传字段路径不能为空");
        requireText(userId, "当前用户不能为空");
        requireText(authorization, "当前请求缺少Authorization");

        if (file == null || file.isEmpty()) {
            throw badRequest("上传文件不能为空");
        }

        CapabilityDefinition writeCapability =getRequiredCapability(writeCapabilityCode);

        if (!"WRITE".equalsIgnoreCase(writeCapability.getSideEffect())) {
            throw badRequest(
                    "目标能力不是WRITE能力："
                            + writeCapabilityCode
            );
        }

        /*
         * 中文注释：
         * 上传能力编码只能从WRITE能力Schema读取，
         * 不能使用Controller请求参数直接指定。
         */
        CapabilityUiSchemaParser.UiSchema writeSchema =
                uiSchemaParser.parse(
                        writeCapability.getInputSchemaJson()
                );

        CapabilityUiSchemaParser.Field uploadField = writeSchema.findField(fieldPath);

        if (uploadField == null) {
            throw badRequest(
                    "WRITE能力未声明上传字段："
                            + fieldPath
            );
        }

        if (!"FILE_UPLOAD".equals(uploadField.component())) {
            throw badRequest(
                    "字段不是文件上传类型："
                            + fieldPath
            );
        }

        CapabilityUiSchemaParser.UploadSource uploadSource =
                uploadField.uploadSource();

        if (uploadSource == null) {
            throw badRequest(
                    "文件字段未配置uploadSource："
                            + fieldPath
            );
        }

        validateFileSize(file, uploadSource);

        CapabilityDefinition uploadCapability =
                getRequiredCapability(
                        uploadSource.capabilityCode()
                );

        validateUploadCapability(uploadCapability);

        /*
         * 中文注释：
         * 文件上传本身属于WRITE操作，
         * 调用业务接口前重新读取PM真实权限。
         */
        permissionVerifier.verifyWritePermission(
                uploadCapability,
                authorization
        );

        Map<String, Object> safeForm =
                form == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(form);

        CapabilityInvocationContext invocationContext =
                createInvocationContext(
                        uploadCapability.getCapabilityCode(),
                        safeForm,
                        userId,
                        authorization
                );

        /*
         * 先复用现有请求构建器完成：
         * Endpoint安全解析、Path、Query、普通Body参数、
         * Authorization和幂等请求头处理。
         */
        CapabilityHttpRequest baseRequest = httpRequestBuilder.build(
                        uploadCapability,
                        invocationContext,
                        createIdempotencyKey());

        CapabilityHttpRequest multipartRequest =
                buildMultipartRequest(
                        baseRequest,
                        uploadSource,
                        file);

        Object rawResponse =
                httpInvoker.invoke(multipartRequest);

        ResponseInterpretationResult interpreted =
                responseInterpreter.interpret(
                        uploadCapability,
                        rawResponse,
                        false
                );

        if (!interpreted.success()) {
            throw new BusinessException(
                    502,
                    StringUtils.hasText(interpreted.errorMessage())
                            ? interpreted.errorMessage()
                            : "上传能力返回失败"
            );
        }

        Object value = readResultValue(
                interpreted.data(),
                uploadSource.resultValuePath()
        );

        /*
         * 中文注释：
         * 只有配置了resultObjectPath才提取文件对象。
         * 旧的上传配置不会返回item，也不会改变原有提交结构。
         */
        Object item = readResultObject(
                interpreted.data(),
                uploadSource.resultObjectPath()
        );

        return CapabilityFileUploadVO.builder()
                .value(value)
                .label(resolveFileName(file))
                .item(item)
                .build();
    }
    /**
     * 从上传能力标准data中提取完整文件对象。
     *
     * 例如：
     * responseBinding.dataPath = $.data
     * resultObjectPath = $
     *
     * 此时返回整个data对象。
     */
    private Object readResultObject(
            Object data,
            String resultObjectPath) {

        /*
         * 中文注释：
         * 没有配置对象路径时保持旧上传协议，
         * 返回null即可。
         */
        if (!StringUtils.hasText(resultObjectPath)) {
            return null;
        }

        JsonNode root = data == null
                        ? objectMapper.nullNode()
                        : objectMapper.valueToTree(data);

        SimpleJsonPathReader.ReadResult result =
                jsonPathReader.read(
                        root,
                        resultObjectPath
                );

        if (!result.found() || result.value() == null || result.value().isNull()) {

            throw new BusinessException(
                    502,
                    "上传能力未返回文件对象，resultObjectPath="
                            + resultObjectPath
            );
        }

        /*
         * 文件对象必须是JSON对象，不能是字符串、数组或数字。
         */
        if (!result.value().isObject()) {
            throw new BusinessException(
                    502,
                    "上传能力文件对象路径不是JSON对象，resultObjectPath="
                            + resultObjectPath
            );
        }
        return objectMapper.convertValue(
                result.value(),
                Object.class
        );
    }
    /**
     * 创建现有能力请求构建器需要的上下文。
     */
    private CapabilityInvocationContext createInvocationContext(
            String uploadCapabilityCode,
            Map<String, Object> form,
            String userId,
            String authorization) {

        PlanStep step = PlanStep.builder()
                .stepType(StepType.BUSINESS_TOOL)
                .stepName("上传动态表单文件")
                .capabilityCode(uploadCapabilityCode)
                .input(form)
                .outputKey("uploadResult")
                .build();

        ToolExecutionContext context =
                ToolExecutionContext.builder()
                        .runId(
                                "upload_"
                                        + UUID.randomUUID()
                                        .toString()
                                        .replace("-", "")
                        )
                        .userId(userId)
                        .authorization(authorization)
                        .variables(new LinkedHashMap<>())
                        .userContext(new LinkedHashMap<>())
                        .secureContext(new LinkedHashMap<>())
                        .build();

        return invocationContextFactory.create(
                context,
                step
        );
    }

    /**
     * 将现有请求转换成multipart请求。
     */
    private CapabilityHttpRequest buildMultipartRequest(
            CapabilityHttpRequest baseRequest,
            CapabilityUiSchemaParser.UploadSource uploadSource,
            MultipartFile file) {

        MultipartBodyBuilder multipart =
                new MultipartBodyBuilder();

        /*
         * 中文注释：
         * 上传能力requestBindingJson产生的普通BODY参数
         * 继续作为multipart普通表单项发送。
         */
        addBodyParts(
                multipart,
                baseRequest.getBody(),
                uploadSource.fileParameterName()
        );

        MediaType fileContentType =
                resolveFileContentType(file);

        multipart.part(
                        uploadSource.fileParameterName(),
                        file.getResource()
                )
                .filename(resolveFileName(file))
                .contentType(fileContentType);

        HttpHeaders headers = new HttpHeaders();
        headers.addAll(baseRequest.getHeaders());

        /*
         * Content-Length必须重新计算。
         * multipart边界由Spring HTTP消息转换器生成。
         */
        headers.remove(HttpHeaders.CONTENT_LENGTH);
        headers.setContentType(
                MediaType.MULTIPART_FORM_DATA
        );

        return CapabilityHttpRequest.builder()
                .method(baseRequest.getMethod())
                .uri(baseRequest.getUri())
                .headers(headers)
                .body(multipart.build())
                .timeoutMs(baseRequest.getTimeoutMs())
                .auditInput(baseRequest.getAuditInput())
                .build();
    }

    /**
     * 把原请求中的普通BODY字段转换为multipart表单项。
     */
    private void addBodyParts(
            MultipartBodyBuilder multipart,
            Object body,
            String fileParameterName) {

        if (body == null) {
            return;
        }

        if (!(body instanceof Map<?, ?> bodyMap)) {
            throw badRequest(
                    "上传能力的BODY绑定结果必须是JSON对象"
            );
        }

        for (Map.Entry<?, ?> entry : bodyMap.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();

            if (fileParameterName.equals(name)) {
                throw badRequest(
                        "requestBindingJson不能重复绑定文件参数："
                                + fileParameterName
                );
            }

            if (value == null) {
                continue;
            }

            if (isScalar(value)) {
                multipart.part(
                        name,
                        String.valueOf(value)
                );
                continue;
            }

            /*
             * 对象和集合使用JSON multipart项，
             * 不拆成多层参数，避免引入第二套路径绑定协议。
             */
            multipart.part(
                            name,
                            writeJson(value)
                    )
                    .contentType(
                            MediaType.APPLICATION_JSON
                    );
        }
    }

    /**
     * 从上传能力标准data中读取最终文件值。
     */
    private Object readResultValue(
            Object data,
            String resultValuePath) {

        JsonNode root =
                data == null
                        ? objectMapper.nullNode()
                        : objectMapper.valueToTree(data);

        SimpleJsonPathReader.ReadResult result =
                jsonPathReader.read(
                        root,
                        resultValuePath
                );

        if (!result.found()
                || result.value() == null
                || result.value().isNull()
                || result.value().isContainerNode()) {
            throw new BusinessException(
                    502,
                    "上传能力未返回有效文件值，resultValuePath="
                            + resultValuePath
            );
        }

        return objectMapper.convertValue(
                result.value(),
                Object.class
        );
    }

    /**
     * 服务端再次校验文件大小，不能只依赖前端限制。
     */
    private void validateFileSize(
            MultipartFile file,
            CapabilityUiSchemaParser.UploadSource uploadSource) {

        long maxBytes =
                uploadSource.maxSizeMb()
                        * 1024L
                        * 1024L;

        if (file.getSize() > maxBytes) {
            throw badRequest(
                    "单个文件不能超过"
                            + uploadSource.maxSizeMb()
                            + "MB"
            );
        }
    }

    /**
     * 上传能力必须明确是POST multipart WRITE能力。
     */
    private void validateUploadCapability(
            CapabilityDefinition capability) {

        if (!"WRITE".equalsIgnoreCase(
                capability.getSideEffect())) {
            throw badRequest(
                    "上传能力必须是WRITE："
                            + capability.getCapabilityCode()
            );
        }

        if (!HttpMethod.POST.matches(
                capability.getMethod())) {
            throw badRequest(
                    "上传能力必须使用POST："
                            + capability.getCapabilityCode()
            );
        }

        String contentType =
                capability.getRequestContentType();

        if (!StringUtils.hasText(contentType)) {
            throw badRequest(
                    "上传能力必须配置requestContentType=multipart/form-data"
            );
        }

        try {
            MediaType configured =
                    MediaType.parseMediaType(contentType);

            if (!MediaType.MULTIPART_FORM_DATA
                    .isCompatibleWith(configured)) {
                throw badRequest(
                        "上传能力requestContentType必须是multipart/form-data"
                );
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw badRequest(
                    "上传能力requestContentType格式不合法"
            );
        }
    }

    private CapabilityDefinition getRequiredCapability(
            String capabilityCode) {

        CapabilityDefinition capability =
                capabilityDefinitionService
                        .getEnabledByCode(capabilityCode);

        if (capability == null) {
            throw new BusinessException(
                    404,
                    "能力不存在、未启用或未发布："
                            + capabilityCode
            );
        }

        return capability;
    }

    private MediaType resolveFileContentType(
            MultipartFile file) {

        if (!StringUtils.hasText(
                file.getContentType())) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }

        try {
            return MediaType.parseMediaType(
                    file.getContentType()
            );
        } catch (Exception exception) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private String resolveFileName(
            MultipartFile file) {

        String fileName =
                StringUtils.cleanPath(
                        file.getOriginalFilename() == null
                                ? "file"
                                : file.getOriginalFilename()
                );

        if (!StringUtils.hasText(fileName)
                || fileName.contains("..")) {
            throw badRequest("上传文件名称不合法");
        }

        return fileName;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw badRequest(
                    "multipart参数无法转换为JSON"
            );
        }
    }

    private boolean isScalar(Object value) {
        return value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character;
    }

    private String createIdempotencyKey() {
        return "upload-"
                + UUID.randomUUID()
                .toString()
                .replace("-", "");
    }

    private void requireText(
            String value,
            String message) {

        if (!StringUtils.hasText(value)) {
            throw badRequest(message);
        }
    }

    private BusinessException badRequest(
            String message) {

        return new BusinessException(
                400,
                message
        );
    }
}