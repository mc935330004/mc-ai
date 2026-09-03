package org.example.ai.agent.business.snapshot.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.business.snapshot.entity.BusinessSnapshot;

import java.util.List;

@Mapper
public interface BusinessSnapshotMapper extends BaseMapper<BusinessSnapshot> {

    /**
     * 锁定本批可安全清理的过期快照，具体引用保护条件统一维护在Mapper XML。
     */
    List<String> selectExpiredUnreferencedIdsForUpdate(
            @Param("batchSize") int batchSize
    );

    /**
     * 清理前再次检查所有引用，避免候选查询后的并发写入造成误删。
     */
    int deleteExpiredUnreferencedById(@Param("snapshotId") String snapshotId);

    /**
     * 聚合引用创建方锁定模块快照，与清理任务使用相同的行锁约定。
     */
    List<BusinessSnapshot> selectByIdsForUpdate(
            @Param("snapshotIds") List<String> snapshotIds
    );
}
