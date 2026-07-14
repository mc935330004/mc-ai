package org.example.ai.agent.capability.index;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 业务能力向量索引服务。
 *
 * 使用确定性 Document ID：
 *
 * capability:pm.project.page
 *
 * 同一能力重新写入时，PGVectorStore 会根据 ID 更新旧记录，
 * 不会无限产生重复向量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(VectorStore.class)
public class CapabilityVectorIndexService {

    private static final String DOCUMENT_TYPE =
            "CAPABILITY";

    private final VectorStore vectorStore;
    private final CapabilitySemanticTextBuilder semanticTextBuilder;
    private final CapabilityDefinitionService capabilityDefinitionService;

    /**
     * 新增或更新单个能力向量。
     */
    public void index(
            CapabilityDefinition capability) {

        if (capability == null
                || !StringUtils.hasText(
                capability.getCapabilityCode())) {
            throw new IllegalArgumentException(
                    "能力及 capabilityCode 不能为空"
            );
        }

        /*
         * 非发布或停用能力不能留在可检索索引中。
         */
        if (!isCallable(capability)) {
            delete(
                    capability.getCapabilityCode()
            );
            return;
        }

        String semanticText =
                semanticTextBuilder.build(capability);

        if (!StringUtils.hasText(semanticText)) {
            throw new IllegalArgumentException(
                    "能力语义文本不能为空："
                            + capability.getCapabilityCode()
            );
        }

        Document document =
                Document.builder()
                        .id(buildDocumentId(
                                capability.getCapabilityCode()
                        ))
                        .text(semanticText)
                        .metadata(Map.of(
                                "document_type",
                                DOCUMENT_TYPE,
                                "capability_code",
                                safe(capability.getCapabilityCode()),
                                "domain",
                                safe(capability.getDomain()),
                                "module_name",
                                safe(capability.getModuleName()),
                                "side_effect",
                                safe(capability.getSideEffect()),
                                "publish_status",
                                safe(capability.getPublishStatus())
                        ))
                        .build();

        vectorStore.add(
                List.of(document)
        );

        log.info(
                "能力向量索引已更新: capabilityCode={}",
                capability.getCapabilityCode()
        );
    }

    /**
     * 删除单个能力向量。
     *
     * 使用 Document ID 精确删除，不影响知识库向量。
     */
    public void delete(String capabilityCode) {
        if (!StringUtils.hasText(capabilityCode)) {
            return;
        }

        vectorStore.delete(
                List.of(
                        buildDocumentId(capabilityCode)
                )
        );

        log.info(
                "能力向量索引已删除: capabilityCode={}",
                capabilityCode
        );
    }

    /**
     * 重建全部能力向量。
     *
     * 风险：
     * 会先删除 document_type=CAPABILITY 的全部向量，
     * 但不会删除知识库向量。
     */
    public CapabilityIndexRebuildResultVO rebuildAll() {
        LocalDateTime startedAt = LocalDateTime.now();

        /*
         * 只删除能力类型向量。
         */
        vectorStore.delete(
                "document_type == 'CAPABILITY'"
        );

        List<CapabilityDefinition> capabilities =
                capabilityDefinitionService
                        .listAgentCallableCapabilities();

        List<String> failedCodes =
                new ArrayList<>();

        int successCount = 0;

        for (CapabilityDefinition capability : capabilities) {
            try {
                index(capability);
                successCount++;
            } catch (Exception exception) {
                failedCodes.add(
                        capability.getCapabilityCode()
                );

                log.error(
                        "能力向量重建失败: capabilityCode={}, error={}",
                        capability.getCapabilityCode(),
                        exception.getMessage(),
                        exception
                );
            }
        }

        return CapabilityIndexRebuildResultVO.builder()
                .totalCount(capabilities.size())
                .successCount(successCount)
                .failedCount(failedCodes.size())
                .failedCapabilityCodes(failedCodes)
                .startedAt(startedAt)
                .finishedAt(LocalDateTime.now())
                .build();
    }

    private boolean isCallable(
            CapabilityDefinition capability) {

        boolean enabled = Integer.valueOf(1)
                        .equals(capability.getEnabled());

        boolean published =
                "PUBLISHED".equalsIgnoreCase(
                        capability.getPublishStatus() )
                        || capability.getPublishStatus() == null;

        return enabled && published;
    }

    /**
     * 根据 capabilityCode 生成稳定 UUID。
     */
    private String buildDocumentId(
            String capabilityCode) {

        return UUID.nameUUIDFromBytes(
                ("capability:" + capabilityCode)
                        .getBytes(StandardCharsets.UTF_8)
        ).toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}