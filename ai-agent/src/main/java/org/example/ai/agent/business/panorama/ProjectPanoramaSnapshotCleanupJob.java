package org.example.ai.agent.business.panorama;

import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotMapper;
import org.example.ai.agent.business.panorama.mapper.ProjectPanoramaSnapshotModuleMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 分批清理已过期的项目全景聚合快照。
 */
@Component
@ConditionalOnProperty(
        prefix = "ai.business.panorama.cleanup",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class ProjectPanoramaSnapshotCleanupJob {

    private final ProjectPanoramaSnapshotMapper snapshotMapper;
    private final ProjectPanoramaSnapshotModuleMapper moduleMapper;
    private final int batchSize;

    public ProjectPanoramaSnapshotCleanupJob(
            ProjectPanoramaSnapshotMapper snapshotMapper,
            ProjectPanoramaSnapshotModuleMapper moduleMapper,
            @Value("${ai.business.panorama.cleanup.batch-size:200}") int batchSize) {
        this.snapshotMapper = Objects.requireNonNull(snapshotMapper, "snapshotMapper不能为空");
        this.moduleMapper = Objects.requireNonNull(moduleMapper, "moduleMapper不能为空");
        if (batchSize < 1 || batchSize > 5000) {
            throw new IllegalArgumentException("项目全景快照清理批次必须在1至5000之间");
        }
        this.batchSize = batchSize;
    }

    /**
     * 先锁定并删除模块引用，再删除聚合主记录，避免留下孤立引用。
     */
    @Scheduled(cron = "${ai.business.panorama.cleanup.cron:0 10 3 * * *}")
    @Transactional(rollbackFor = Exception.class)
    public CleanupResult cleanupBatch() {
        List<String> candidates = snapshotMapper.selectExpiredIdsForUpdate(batchSize);
        int deletedReferences = 0;
        int deletedSnapshots = 0;
        for (String aggregateId : candidates) {
            deletedReferences += moduleMapper.deleteByPanoramaSnapshotId(aggregateId);
            deletedSnapshots += snapshotMapper.deleteExpiredByIdWithoutModules(aggregateId);
        }
        return new CleanupResult(deletedReferences, deletedSnapshots);
    }

    public record CleanupResult(
            int deletedModuleReferences,
            int deletedSnapshots) {
    }
}
