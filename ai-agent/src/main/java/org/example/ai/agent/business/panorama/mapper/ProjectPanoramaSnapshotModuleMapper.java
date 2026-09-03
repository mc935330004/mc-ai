package org.example.ai.agent.business.panorama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.business.panorama.entity.ProjectPanoramaSnapshotModule;

/**
 * 项目全景模块快照引用数据访问。
 */
@Mapper
public interface ProjectPanoramaSnapshotModuleMapper
        extends BaseMapper<ProjectPanoramaSnapshotModule> {

    int deleteByPanoramaSnapshotId(
            @Param("panoramaSnapshotId") String panoramaSnapshotId
    );
}
