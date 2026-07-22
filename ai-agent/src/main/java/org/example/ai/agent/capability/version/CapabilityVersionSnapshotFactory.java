package org.example.ai.agent.capability.version;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.CapabilityVersion;
import org.example.ai.agent.capability.invocation.service.CapabilityBindingConfigurationService;
import org.example.ai.agent.capability.version.model.CapabilityPublishedSnapshot;
import org.example.ai.agent.capability.version.model.CapabilitySnapshotMaterial;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * 能力发布快照工厂。
 *
 * 职责：
 * 1. 将能力草稿转换成不可变发布快照；
 * 2. 对 JSON 对象字段进行递归排序；
 * 3. 生成稳定 SHA-256；
 * 4. 将历史发布快照恢复成运行时能力。
 */
@Component
@RequiredArgsConstructor
public class CapabilityVersionSnapshotFactory {

    private static final String SUPPORTED_SPEC_VERSION = "1.0";

    private final ObjectMapper objectMapper;
    private final CapabilityBindingConfigurationService bindingService;

    /**
     * 从当前能力草稿生成发布材料。
     */
    public CapabilitySnapshotMaterial create(
            CapabilityDefinition capability) {

        if (capability == null) {
            throw new IllegalArgumentException("能力配置不能为空");
        }

        /*
         * 发布快照生成前再次校验输入 Schema 与请求绑定，
         * 确保不可变版本快照中不存在错误字段。
         */
        String requestBindingJson =bindingService.normalizeRequestBinding(
                        capability.getCapabilityCode(),
                        capability.getMethod(),
                        capability.getUrl(),
                        capability.getInputSchemaJson(),
                        capability.getRequestBindingJson());

        if (!StringUtils.hasText(requestBindingJson)) {
            throw new BusinessException(
                    400,
                    "能力发布前必须配置requestBindingJson：" +
                            capability.getCapabilityCode()
            );
        }

        String responseBindingJson =
                bindingService.normalizeResponseBinding(
                        capability.getResponseBindingJson()
                );

        CapabilityPublishedSnapshot snapshot =
                CapabilityPublishedSnapshot.builder()
                        .specVersion(SUPPORTED_SPEC_VERSION)
                        .capabilityCode(capability.getCapabilityCode())
                        .capabilityName(capability.getCapabilityName())
                        .domain(capability.getDomain())
                        .moduleName(capability.getModuleName())
                        .description(capability.getDescription())
                        .systemCode(capability.getSystemCode())
                        .method(capability.getMethod())
                        .url(capability.getUrl())
                        .requestContentType(
                                capability.getRequestContentType()
                        )
                        .timeoutMs(capability.getTimeoutMs())
                        .sideEffect(capability.getSideEffect())
                        .requireConfirm(capability.getRequireConfirm())
                        .inputSchema(readRequiredObject(
                                "inputSchemaJson",
                                capability.getInputSchemaJson()
                        ))
                        .outputSchema(readRequiredObject(
                                "outputSchemaJson",
                                capability.getOutputSchemaJson()
                        ))
                        .requestBinding(readRequiredObject(
                                "requestBindingJson",
                                requestBindingJson
                        ))
                        .responseBinding(readOptionalObject(
                                "responseBindingJson",
                                responseBindingJson
                        ))
                        .example(readOptionalJsonOrText(
                                capability.getExampleJson()
                        ))
                        .sourceType(capability.getSourceType())
                        .sourceOperationId(
                                capability.getSourceOperationId()
                        )
                        .build();

        JsonNode rawNode = objectMapper.valueToTree(snapshot);
        JsonNode canonicalNode = canonicalize(rawNode);
        String snapshotJson = writeJson(canonicalNode);

        return new CapabilitySnapshotMaterial(
                snapshotJson,
                sha256(snapshotJson)
        );
    }

    /**
     * 把不可变快照恢复为运行时 CapabilityDefinition。
     *
     * registryDefinition 只提供运行状态；
     * HTTP、Schema、绑定等配置全部来自版本快照。
     */
    public CapabilityDefinition restore(CapabilityDefinition registryDefinition,CapabilityVersion version) {
        if (registryDefinition == null || version == null) {
            throw new IllegalArgumentException(
                    "能力定义和能力版本不能为空"
            );
        }
        CapabilityPublishedSnapshot snapshot;
        try {
            snapshot = objectMapper.readValue(version.getSnapshotJson(), CapabilityPublishedSnapshot.class );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "能力发布快照解析失败，versionId=" +
                            version.getId(),
                    exception
            );
        }

        if (!SUPPORTED_SPEC_VERSION.equals(
                snapshot.getSpecVersion()
        )) {
            throw new IllegalStateException(
                    "不支持的能力快照版本：" +
                            snapshot.getSpecVersion()
            );
        }

        if (!Objects.equals(
                registryDefinition.getCapabilityCode(),
                snapshot.getCapabilityCode()
        )) {
            throw new IllegalStateException(
                    "能力版本与能力定义编码不一致，versionId=" +
                            version.getId()
            );
        }

        CapabilityDefinition runtime =
                new CapabilityDefinition();

        runtime.setId(registryDefinition.getId());
        runtime.setCapabilityCode(snapshot.getCapabilityCode());
        runtime.setCapabilityName(snapshot.getCapabilityName());
        runtime.setDomain(snapshot.getDomain());
        runtime.setModuleName(snapshot.getModuleName());
        runtime.setDescription(snapshot.getDescription());

        runtime.setSystemCode(snapshot.getSystemCode());
        runtime.setMethod(snapshot.getMethod());
        runtime.setUrl(snapshot.getUrl());
        runtime.setRequestContentType(
                snapshot.getRequestContentType()
        );
        runtime.setTimeoutMs(snapshot.getTimeoutMs());

        runtime.setSideEffect(snapshot.getSideEffect());
        runtime.setRequireConfirm(snapshot.getRequireConfirm());

        runtime.setInputSchemaJson(
                writeNullableJson(snapshot.getInputSchema())
        );
        runtime.setOutputSchemaJson(
                writeNullableJson(snapshot.getOutputSchema())
        );
        runtime.setRequestBindingJson(
                writeNullableJson(snapshot.getRequestBinding())
        );
        runtime.setResponseBindingJson(
                writeNullableJson(snapshot.getResponseBinding())
        );
        runtime.setExampleJson(
                writeExample(snapshot.getExample())
        );

        runtime.setSourceType(snapshot.getSourceType());
        runtime.setSourceOperationId(
                snapshot.getSourceOperationId()
        );

        /*
         * 运行状态来自能力主表和版本记录。
         */
        runtime.setEnabled(registryDefinition.getEnabled());
        runtime.setPublishStatus(
                registryDefinition.getPublishStatus()
        );
        runtime.setActiveVersionId(version.getId());
        runtime.setConfigRevision(version.getConfigRevision());
        runtime.setConfigChecksum(version.getConfigChecksum());
        runtime.setValidatedAt(version.getPublishedAt());
        runtime.setDraftDirty(0);

        return runtime;
    }

    /**
     * 对 JSON 对象字段递归排序。
     *
     * 数组保留原始顺序，因为数组顺序可能具有业务含义。
     */
    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }

        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();

            List<String> fieldNames = new ArrayList<>();
            Iterator<String> iterator = node.fieldNames();
            iterator.forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);

            for (String fieldName : fieldNames) {
                sorted.set(
                        fieldName,
                        canonicalize(node.get(fieldName))
                );
            }

            return sorted;
        }

        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();

            for (JsonNode child : node) {
                array.add(canonicalize(child));
            }

            return array;
        }

        return node.deepCopy();
    }

    private JsonNode readRequiredObject(
            String fieldName,
            String json) {

        if (!StringUtils.hasText(json)) {
            throw new BusinessException(
                    400,
                    fieldName + "不能为空"
            );
        }

        JsonNode node = readJson(fieldName, json);

        if (!node.isObject()) {
            throw new BusinessException(
                    400,
                    fieldName + "必须是JSON对象"
            );
        }

        return node;
    }

    private JsonNode readOptionalObject(
            String fieldName,
            String json) {

        if (!StringUtils.hasText(json)) {
            return null;
        }

        return readRequiredObject(fieldName, json);
    }

    private JsonNode readOptionalJsonOrText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }

        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException ignored) {
            /*
             * exampleJson 历史数据可能是普通文本，
             * 不影响运行时执行，因此可以保存为文本节点。
             */
            return objectMapper
                    .getNodeFactory()
                    .textNode(value.trim());
        }
    }

    private JsonNode readJson(
            String fieldName,
            String json) {

        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    400,
                    fieldName + "不是合法JSON"
            );
        }
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "能力发布快照序列化失败",
                    exception
            );
        }
    }

    private String writeNullableJson(JsonNode node) {
        return node == null || node.isNull()
                ? null
                : writeJson(node);
    }

    private String writeExample(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }

        return node.isTextual()
                ? node.asText()
                : writeJson(node);
    }

    private String sha256(String source) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");

            byte[] bytes = digest.digest(
                    source.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "Java运行环境不支持SHA-256",
                    exception
            );
        }
    }
}