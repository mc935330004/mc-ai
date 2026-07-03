package org.example.airag.modules.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBaseVectorTask;

/**
 * 知识库向量化任务 Mapper
 */
@Mapper
public interface KnowledgeBaseVectorTaskMapper extends BaseMapper<KnowledgeBaseVectorTask> {

    /**
     * 修改锁定待处理任务
     * @param taskId
     * @param lockOwner
     * @return
     */
    int lockPendingTask(@Param("taskId") Long taskId, @Param("lockOwner") String lockOwner);

    /**
     * 重置超时任务
     * @param timeoutMinutes
     * @return
     */
    int resetTimeoutTasks(@Param("timeoutMinutes") int timeoutMinutes);

    /**
     * 失败超时任务
     * @param timeoutMinutes
     * @return
     */
    int failTimeoutTasks(@Param("timeoutMinutes") int timeoutMinutes);
}