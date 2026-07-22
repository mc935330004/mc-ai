package org.example.ai.agent.capability.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.entity.CapabilityVersion;
import org.example.ai.agent.capability.mapper.CapabilityVersionMapper;
import org.example.ai.agent.capability.service.CapabilityVersionService;
import org.example.ai.agent.capability.version.model.CapabilitySnapshotMaterial;
import org.example.ai.agent.capability.version.model.CapabilityVersionPublishResult;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 能力不可变版本服务。
 *
 * 注意：
 * publishSnapshot 必须在已经锁定能力定义行的事务中调用。
 */
@Service
@RequiredArgsConstructor
public class CapabilityVersionServiceImpl extends ServiceImpl<CapabilityVersionMapper, CapabilityVersion>
        implements CapabilityVersionService {

    private final CapabilityVersionMapper capabilityVersionMapper;

    @Override
    public CapabilityVersionPublishResult publishSnapshot(
            CapabilityDefinition capability,
            CapabilitySnapshotMaterial material,
            String publishedBy) {

        if (capability == null || capability.getId() == null) {
            throw new IllegalArgumentException(
                    "能力定义及其ID不能为空"
            );
        }

        if (material == null
                || !StringUtils.hasText(material.snapshotJson())
                || !StringUtils.hasText(material.configChecksum())) {
            throw new IllegalArgumentException(
                    "能力发布快照不能为空"
            );
        }

        if (!StringUtils.hasText(publishedBy)) {
            throw new BusinessException(
                    400,
                    "发布用户不能为空"
            );
        }

        CapabilityVersion activeVersion = null;

        if (capability.getActiveVersionId() != null) {
            activeVersion = capabilityVersionMapper.selectById(
                    capability.getActiveVersionId()
            );

            if (activeVersion == null) {
                throw new IllegalStateException(
                        "activeVersionId对应版本不存在：" +
                                capability.getActiveVersionId()
                );
            }

            if (!Objects.equals(
                    activeVersion.getCapabilityId(),
                    capability.getId()
            )) {
                throw new IllegalStateException(
                        "activeVersionId不属于当前能力"
                );
            }

            /*
             * 草稿内容与当前发布版本完全一致时，
             * 复用原版本，不产生无意义的新版本号。
             */
            if (Objects.equals(
                    activeVersion.getConfigChecksum(),
                    material.configChecksum()
            )) {
                return new CapabilityVersionPublishResult(
                        activeVersion,
                        true
                );
            }
        }

        CapabilityVersion latest =
                capabilityVersionMapper
                        .selectLatestByCapabilityId(
                                capability.getId()
                        );

        int nextVersionNo =
                latest == null
                        ? 1
                        : latest.getVersionNo() + 1;

        LocalDateTime now = LocalDateTime.now();

        /*
         * 先退役旧版本，再插入新版本。
         * 整个方法由外层事务保护，任何一步失败都会回滚。
         */
        capabilityVersionMapper.retireActiveVersions(
                capability.getId()
        );

        CapabilityVersion newVersion =
                new CapabilityVersion();

        newVersion.setCapabilityId(capability.getId());
        newVersion.setCapabilityCode(
                capability.getCapabilityCode()
        );
        newVersion.setVersionNo(nextVersionNo);
        newVersion.setConfigRevision(
                capability.getConfigRevision()
        );
        newVersion.setSnapshotJson(material.snapshotJson());
        newVersion.setConfigChecksum(
                material.configChecksum()
        );
        newVersion.setStatus("ACTIVE");
        newVersion.setPublishedBy(publishedBy.trim());
        newVersion.setPublishedAt(now);
        newVersion.setCreatedAt(now);

        int inserted =
                capabilityVersionMapper.insert(newVersion);

        if (inserted != 1 || newVersion.getId() == null) {
            throw new IllegalStateException(
                    "能力版本创建失败：" +
                            capability.getCapabilityCode()
            );
        }

        return new CapabilityVersionPublishResult(
                newVersion,
                false
        );
    }

    @Override
    public CapabilityVersion getRequiredVersion( Long versionId) {

        if (versionId == null) {
            throw new IllegalArgumentException(
                    "versionId不能为空"
            );
        }

        CapabilityVersion version =
                capabilityVersionMapper.selectById(versionId);

        if (version == null) {
            throw new IllegalStateException(
                    "能力版本不存在：" + versionId
            );
        }

        return version;
    }
}