package org.example.ai.agent.modelusage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.modelusage.entity.ModelUsageRecord;
import org.example.ai.agent.modelusage.vo.ModelUsageByModelVO;
import org.example.ai.agent.modelusage.vo.ModelUsageOverviewVO;
import org.example.ai.agent.modelusage.vo.RecentModelFailureVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 大模型调用明细和管理端聚合查询。
 */
@Mapper
public interface ModelUsageMapper extends BaseMapper<ModelUsageRecord> {

    /**
     * 查询时间范围内的总览数据。
     */
    ModelUsageOverviewVO selectOverview(
            @Param("startTime") LocalDateTime startTime
    );

    /**
     * 按模型汇总调用情况。
     */
    List<ModelUsageByModelVO> selectUsageByModel(
            @Param("startTime") LocalDateTime startTime
    );

    /**
     * 查询最近失败记录。
     *
     * SQL 不读取原始 error_message，避免管理接口泄漏供应商响应。
     */
    List<RecentModelFailureVO> selectRecentFailures(
            @Param("startTime") LocalDateTime startTime,
            @Param("limit") int limit
    );
}