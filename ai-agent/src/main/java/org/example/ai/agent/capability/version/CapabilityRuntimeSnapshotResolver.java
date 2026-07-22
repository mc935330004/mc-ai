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

        CapabilityVersion version =versionService.getRequiredVersion(registryDefinition.getActiveVersionId());

        if (!Objects.equals(
                version.getCapabilityId(),
                registryDefinition.getId()
        )) {
            throw new IllegalStateException(
                    "能力发布版本归属不正确：" +
                            version.getId()
            );
        }

        if (!"ACTIVE".equals(version.getStatus())) {
            throw new IllegalStateException(
                    "activeVersionId指向的不是ACTIVE版本：" +
                            version.getId()
            );
        }
        return snapshotFactory.restore( registryDefinition,version );
    }
}