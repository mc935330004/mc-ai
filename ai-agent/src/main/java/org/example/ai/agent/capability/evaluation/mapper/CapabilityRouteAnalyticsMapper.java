package org.example.ai.agent.capability.evaluation.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.capability.evaluation.vo.CapabilityConfusionVO;
import org.example.ai.agent.capability.evaluation.vo.CapabilityRouteMetricsVO;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CapabilityRouteAnalyticsMapper {

    CapabilityRouteMetricsVO selectDecisionMetrics(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    CapabilityRouteMetricsVO selectFeedbackMetrics(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    List<CapabilityConfusionVO> selectTopConfusions(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("limit") int limit
    );
}