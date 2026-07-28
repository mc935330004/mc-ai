package org.example.ai.agent.capability.version;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.CapabilityVersion;
import org.example.ai.agent.capability.service.CapabilityVersionService;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 运行时能力快照解析器。
 *
 * 管理端测试可以直接读取草稿；
 * Agent 正常执行必须通过本解析器读取 activeVersion。
 */
@Component
@RequiredArgsConstructor
public class CapabilityRuntimeSnapshotResolver {

    private final CapabilityVersionService versionService;
    private final CapabilityVersionSnapshotFactory snapshotFactory;

    /**
     * 解析当前能力的正式发布版本。
     *
     * 运行时只允许读取 ACTIVE 版本，并校验：
     * 1. 版本归属是否正确；
     * 2. 版本状态是否正确；
     * 3. 快照内容是否被修改；
     * 4. 能力主表是否仍指向同一份发布配置。
     */
    public CapabilityDefinition resolve(
            CapabilityDefinition registryDefinition) {

        if (registryDefinition == null) {
            return null;
        }

        if (registryDefinition.getActiveVersionId() == null) {
            throw new IllegalStateException(
                    "能力没有可用发布版本：" +
                            registryDefinition.getCapabilityCode()
            );
        }

        CapabilityVersion version =
                versionService.getRequiredVersion(
                        registryDefinition.getActiveVersionId()
                );

        if (!Objects.equals(
                version.getCapabilityId(),
                registryDefinition.getId()
        )) {
            throw new IllegalStateException(
                    "能力发布版本归属不正确，versionId=" +
                            version.getId()
            );
        }

        if (!"ACTIVE".equals(version.getStatus())) {
            throw new IllegalStateException(
                    "activeVersionId 指向的不是 ACTIVE 版本，versionId=" +
                            version.getId()
            );
        }

        /*
         * 校验数据库中的能力快照是否被直接修改。
         * 快照内容与发布时保存的 SHA-256 不一致时，立即拒绝运行。
         */
        String actualChecksum =snapshotFactory.checksumRaw(version.getSnapshotJson());

        if (!Objects.equals(actualChecksum,version.getConfigChecksum())) {
            throw new IllegalStateException(
                    "能力版本快照校验和不一致，versionId=" +
                            version.getId()
            );
        }

        /*
         * 校验能力主表与当前活动版本是否仍然一致。
         * 防止只修改主表 activeVersionId 或 configChecksum 绕过版本控制。
         */
        if (!Objects.equals(registryDefinition.getConfigChecksum(),version.getConfigChecksum())) {
            throw new IllegalStateException(
                    "能力定义与活动版本校验和不一致，capabilityCode=" +
                            registryDefinition.getCapabilityCode()
            );
        }

        return snapshotFactory.restore(
                registryDefinition,
                version
        );
    }
}