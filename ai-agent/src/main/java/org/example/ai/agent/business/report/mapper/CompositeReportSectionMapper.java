package org.example.ai.agent.business.report.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.business.report.entity.CompositeReportSection;

import java.util.List;

/** 组合报告章节数据访问；全部SQL统一维护在Mapper XML。 */
@Mapper
public interface CompositeReportSectionMapper {

    int insertSection(@Param("section") CompositeReportSection section);

    List<CompositeReportSection> selectByTaskId(@Param("taskId") String taskId);
}
