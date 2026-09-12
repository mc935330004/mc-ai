package org.example.ai.agent.business.panorama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshot;

import java.util.List;

/**
 * 项目全景聚合快照数据访问。
 */
@Mapper
public interface ProjectPanoramaSnapshotMapper extends BaseMapper<ProjectPanoramaSnapshot> {

    List<String> selectExpiredIdsForUpdate(@Param("batchSize") int batchSize);

    int deleteExpiredByIdWithoutModules(
            @Param("panoramaSnapshotId") String panoramaSnapshotId
    );
}
