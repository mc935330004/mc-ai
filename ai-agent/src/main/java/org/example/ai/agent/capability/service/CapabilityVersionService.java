package org.example.ai.agent.capability.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.CapabilityVersion;
import org.example.ai.agent.capability.version.model.CapabilitySnapshotMaterial;
import org.example.ai.agent.capability.version.model.CapabilityVersionPublishResult;

public interface CapabilityVersionService
        extends IService<CapabilityVersion> {

    CapabilityVersionPublishResult publishSnapshot(
            CapabilityDefinition capability,
            CapabilitySnapshotMaterial material,
            String publishedBy
    );

    CapabilityVersion getRequiredVersion(Long versionId);
}