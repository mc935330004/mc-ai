package org.example.ai.agent.capability.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.AuthorizationValue;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.OpenApiOperationDetailRequest;
import org.example.ai.agent.capability.dto.OpenApiPreviewRequest;
import org.example.ai.agent.capability.entity.BusinessSystem;
import org.example.ai.agent.capability.service.BusinessSystemService;
import org.example.ai.agent.capability.service.OpenApiPreviewService;
import org.example.ai.agent.capability.support.OpenApiSchemaConverter;
import org.example.ai.agent.capability.vo.OpenApiOperationDetailVO;
import org.example.ai.agent.capability.vo.OpenApiOperationPreviewVO;
import org.example.ai.agent.capability.vo.OpenApiPreviewVO;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OpenAPI 扫描预览实现。
 */
@Service
@RequiredArgsConstructor
public class OpenApiPreviewServiceImpl implements OpenApiPreviewService {

    private final BusinessSystemService businessSystemService;
    private final OpenApiSchemaConverter schemaConverter;
    private final ObjectMapper objectMapper;
    /**
     * OpenAPI 文档本地缓存。
     *
     * 缓存中只保存解析后的接口结构，不保存 Authorization。
     */
    private final Map<String, OpenApiCacheEntry> openApiCache =new ConcurrentHashMap<>();
    /**
     * 缓存五分钟，避免预览和批量导入重复解析大文档。
     */
    private static final long CACHE_MILLIS = Duration.ofMinutes(5).toMillis();
    private record OpenApiCacheEntry(
            OpenAPI openAPI,
            String openapiUrl,
            long expireAt ) {
    }
    @Override
    public OpenApiPreviewVO preview(OpenApiPreviewRequest request,String authorization) {
        BusinessSystem system = businessSystemService.getEnabledByCode(request.getSystemCode());
        if (system == null) {
            throw new BusinessException(404,"业务系统不存在或未启用：" + request.getSystemCode());
        }
        if (!StringUtils.hasText(system.getOpenapiUrl())) {
            throw new BusinessException(400,"业务系统未配置 OpenAPI 地址");
        }
        OpenAPI openAPI = readOpenApi(system,authorization);
        List<OpenApiOperationPreviewVO> allInterfaces = parseOperations(system, openAPI);
        List<OpenApiOperationPreviewVO> filtered = filterOperations(allInterfaces, request);
        int current = request.getCurrent() == null|| request.getCurrent() < 1 ? 1
                : request.getCurrent();
        int size = request.getSize() == null || request.getSize() < 1 ? 20
                : Math.min(request.getSize(), 100);
        int fromIndex = Math.min((current - 1) * size,filtered.size());
        int toIndex = Math.min( fromIndex + size,filtered.size());
        List<OpenApiOperationPreviewVO> pageRecords = filtered.subList(fromIndex, toIndex);

        return OpenApiPreviewVO.builder()
                .systemCode(system.getSystemCode())
                .systemName(system.getSystemName())
                .openapiTitle( openAPI.getInfo() == null ? null: openAPI.getInfo().getTitle())
                .openapiVersion(openAPI.getInfo() == null ? null : openAPI.getInfo().getVersion())
                .total(filtered.size())
                .current(current)
                .size(size)
                .interfaces(pageRecords)
                .build();
    }

    @Override
    public OpenApiOperationDetailVO operationDetail(OpenApiOperationDetailRequest request, String authorization) {

        BusinessSystem system = businessSystemService.getEnabledByCode(request.getSystemCode());
        if (system == null) {
            throw new BusinessException(404,"业务系统不存在或未启用");
        }

        OpenAPI openAPI = readOpenApi(system, authorization);
        PathItem pathItem = openAPI.getPaths().get(request.getUrl());
        if (pathItem == null) {
            throw new BusinessException(404,"OpenAPI中不存在接口：" + request.getUrl());
        }
        PathItem.HttpMethod method;
        try {
            method = PathItem.HttpMethod.valueOf(request.getMethod().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new BusinessException(400,"不支持的请求方法：" + request.getMethod());
        }

        Operation operation = pathItem.readOperationsMap().get(method);

        if (operation == null) {
            throw new BusinessException(404,"接口未声明请求方法：" + request.getMethod());
        }

        String sideEffect = inferSideEffect(method,operation.getOperationId(),operation.getSummary());

        return OpenApiOperationDetailVO.builder()
                .operationId(operation.getOperationId())
                .capabilityCode(buildCapabilityCode(
                        system.getSystemCode(),
                        operation.getOperationId(),
                        request.getUrl(),
                        method
                ))
                .capabilityName(firstText(
                        operation.getSummary(),
                        operation.getOperationId(),
                        method.name() + " " + request.getUrl()
                ))
                .method(method.name())
                .url(request.getUrl())
                .sideEffect(sideEffect)
                .requireConfirm(!"READ".equals(sideEffect))
                .inputSchemaJson(buildInputSchema(operation))
                .outputSchemaJson(buildOutputSchema(operation))
                .description(operation.getDescription())
                .tag(operation.getTags() == null || operation.getTags().isEmpty()
                                ? null : operation.getTags().get(0))
                .build();
    }

    /**
     * 读取并解析 OpenAPI 文档。
     */
    private OpenAPI readOpenApiRemote(BusinessSystem system,String authorization) {
        ParseOptions options = new ParseOptions();
        // 解析引用，但不把所有公共模型复制到每一个接口中。
        options.setResolve(true);
        options.setResolveFully(false);
        options.setFlatten(false);
        List<AuthorizationValue> authValues = new ArrayList<>();
        if ("FORWARD".equalsIgnoreCase(system.getAuthType())) {
            if (!StringUtils.hasText(authorization)) {
                throw new BusinessException( 401,"读取 OpenAPI 文档缺少 Authorization");
            }
            AuthorizationValue auth = new AuthorizationValue();
            auth.setKeyName("Authorization");
            auth.setValue(authorization);
            auth.setType("header");
            authValues.add(auth);
        }
        SwaggerParseResult result = new OpenAPIV3Parser()
                .readLocation( system.getOpenapiUrl(), authValues,options);

        if (result.getOpenAPI() == null) {
            String message = result.getMessages() == null ? "未知解析错误" : String.join("；", result.getMessages());
            throw new BusinessException( 400,"OpenAPI文档解析失败：" + message);
        }
        return result.getOpenAPI();
    }
    /**
     * 优先使用缓存中的 OpenAPI 文档。
     */
    private OpenAPI readOpenApi(BusinessSystem system,String authorization) {
        String cacheKey = system.getSystemCode();
        long now = System.currentTimeMillis();
        OpenApiCacheEntry cached = openApiCache.get(cacheKey);
        if (cached != null && cached.expireAt() > now && system.getOpenapiUrl().equals(cached.openapiUrl())) {
            return cached.openAPI();
        }
        OpenAPI openAPI = readOpenApiRemote( system, authorization);

        openApiCache.put(cacheKey, new OpenApiCacheEntry(
                        openAPI,
                        system.getOpenapiUrl(),
                        now + CACHE_MILLIS));
        return openAPI;
    }
    /**
     * 扫描所有 Path 和 Operation。
     */
    private List<OpenApiOperationPreviewVO> parseOperations( BusinessSystem system, OpenAPI openAPI) {
        List<OpenApiOperationPreviewVO> result = new ArrayList<>();
        if (openAPI.getPaths() == null) {
            return result;
        }
        openAPI.getPaths().forEach((path, pathItem) ->
            pathItem.readOperationsMap().forEach((method, operation) ->
                    result.add(buildOperation( system, path,method,operation)))
        );
        return result;
    }

    private OpenApiOperationPreviewVO buildOperation(BusinessSystem system,String path,
            PathItem.HttpMethod method, Operation operation) {
        String operationId = operation.getOperationId();
        String capabilityCode = buildCapabilityCode(
                system.getSystemCode(),
                operationId,
                path,
                method
        );
        String sideEffect = inferSideEffect(method,operationId,operation.getSummary());
        return OpenApiOperationPreviewVO.builder()
                .operationId(operationId)
                .capabilityCode(capabilityCode)
                .capabilityName(firstText(operation.getSummary(),operationId,method.name() + " " + path))
                .description(operation.getDescription())
                .tag(operation.getTags() == null || operation.getTags().isEmpty()
                                ? null
                                : operation.getTags().get(0))
                .method(method.name())
                .url(path)
                .sideEffect(sideEffect)
                .requireConfirm(!"READ".equals(sideEffect))
                // 列表只返回是否存在 Schema，不返回完整内容。
                .hasInputSchema(operation.getParameters() != null
                                && !operation.getParameters().isEmpty()
                                || operation.getRequestBody() != null)
                .hasOutputSchema(hasOutputSchema(operation))
                .build();
    }
    /**
     * 判断接口是否声明成功响应结构。
     */
    private boolean hasOutputSchema(Operation operation) {
        if (operation.getResponses() == null) {
            return false;
        }
        return operation.getResponses()
                .entrySet()
                .stream()
                .filter(entry -> entry.getKey().startsWith("2"))
                .map(Map.Entry::getValue)
                .anyMatch(response -> response.getContent() != null&& !response.getContent().isEmpty());
    }

    private List<OpenApiOperationPreviewVO> filterOperations(List<OpenApiOperationPreviewVO> source,OpenApiPreviewRequest request) {
        return source.stream().filter(item -> {
                    if (!StringUtils.hasText(request.getKeyword())) {
                        return true;
                    }
                    String keyword = request.getKeyword().trim().toLowerCase(Locale.ROOT);
                    return containsIgnoreCase(item.getOperationId(), keyword) || containsIgnoreCase(item.getCapabilityName(),
                            keyword) || containsIgnoreCase( item.getUrl(),keyword);
                }).filter(item ->
                        !StringUtils.hasText(request.getTag()) || request.getTag().trim().equalsIgnoreCase(item.getTag()))
                .toList();
    }

    private boolean containsIgnoreCase(String source,String keyword) {
        return source != null && source.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /**
     * 合并 Query、Path 参数和 JSON RequestBody。
     */
    private String buildInputSchema(Operation operation) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");

        ObjectNode properties = objectMapper.createObjectNode();
        ArrayNode required = objectMapper.createArrayNode();

        if (operation.getParameters() != null) {
            for (Parameter parameter : operation.getParameters()) {
                if (!StringUtils.hasText(parameter.getName())) {
                    continue;
                }
                Schema<?> schema = parameter.getSchema();
                ObjectNode field = schemaConverter.convert(schema);
                if (StringUtils.hasText(parameter.getDescription())) {
                    field.put("description",parameter.getDescription());
                }
                properties.set(parameter.getName(), field);
                if (Boolean.TRUE.equals(parameter.getRequired())) {
                    required.add(parameter.getName());
                }
            }
        }

        Schema<?> bodySchema = findJsonSchema(operation.getRequestBody());
        if (bodySchema != null
                && bodySchema.getProperties() != null) {
            ObjectNode bodyNode = schemaConverter.convert(bodySchema);
//            bodyNode.withObject("/properties")
//                    .fields()
//                    .forEachRemaining(entry ->
//                            properties.set(entry.getKey(), entry.getValue())
//                    );
            JsonNode bodyProperties = bodyNode.get("properties");
            if (bodyProperties != null && bodyProperties.isObject()) {
                bodyProperties.fields().forEachRemaining(entry ->
                        properties.set(entry.getKey(),entry.getValue())
                );
            }

            if (bodySchema.getRequired() != null) {
                bodySchema.getRequired().forEach(required::add);
            }
        }
        root.set("properties", properties);
        root.set("required", required);

        return writeJson(root);
    }

    /**
     * 优先读取 200，其次读取第一个 2xx 响应。
     */
    private String buildOutputSchema(Operation operation) {
        if (operation.getResponses() == null) {
            return "{}";
        }
        ApiResponse response = operation.getResponses().get("200");
        if (response == null) {
            response = operation.getResponses().entrySet()
                    .stream()
                    .filter(entry ->
                            entry.getKey().startsWith("2") )
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (response == null || response.getContent() == null) {
            return "{}";
        }
        Schema<?> schema = findJsonSchema(response.getContent());
        return schemaConverter.toJson(schema);
    }

    private Schema<?> findJsonSchema(RequestBody requestBody) {
        return requestBody == null ? null : findJsonSchema(requestBody.getContent());
    }

    private Schema<?> findJsonSchema(Content content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        MediaType json = content.get("application/json");

        if (json == null) {
            json = content.values().iterator().next();
        }

        return json == null ? null : json.getSchema();
    }

    /**
     * 根据 HTTP Method 和名称生成默认副作用等级。
     */
    private String inferSideEffect(PathItem.HttpMethod method,String operationId,String summary) {
        if (method == PathItem.HttpMethod.GET
                || method == PathItem.HttpMethod.HEAD
                || method == PathItem.HttpMethod.OPTIONS) {
            return "READ";
        }
        if (method == PathItem.HttpMethod.DELETE) {
            return "DANGEROUS";
        }

        String text = (firstText(operationId, "")+ " "
                        + firstText(summary, "")).toLowerCase(Locale.ROOT);
        if (containsAny( text,
                "query",
                "page",
                "list",
                "search",
                "detail",
                "查询",
                "列表",
                "详情")) {
            return "READ";
        }
        return "WRITE";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String buildCapabilityCode(String systemCode,String operationId,String path,PathItem.HttpMethod method ) {
        String suffix = StringUtils.hasText(operationId)
                ? operationId
                : method.name().toLowerCase(Locale.ROOT)
                + "."
                + path.replaceAll("[^a-zA-Z0-9]+", ".");
        suffix = suffix
                .replaceAll("^\\.+|\\.+$", "")
                .replaceAll("\\.{2,}", ".");
        return systemCode.toLowerCase(Locale.ROOT)+ "."+ suffix;
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String writeJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON Schema序列化失败",e);
        }
    }
}