package org.example.ai.agent.business.panorama.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.business.panorama.entity.ProjectIssueRule;

/**
 * 项目确定性问题规则数据访问入口。
 */
@Mapper
public interface ProjectIssueRuleMapper extends BaseMapper<ProjectIssueRule> {
}
