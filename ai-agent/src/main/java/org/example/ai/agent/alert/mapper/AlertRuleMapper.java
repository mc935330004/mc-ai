package org.example.ai.agent.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.alert.entity.AlertRule;

/**
 * 告警规则数据访问接口。
 *
 * 当前都是简单单表操作，直接使用 BaseMapper，
 * 不需要创建 Mapper XML。
 */
@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRule> {
}