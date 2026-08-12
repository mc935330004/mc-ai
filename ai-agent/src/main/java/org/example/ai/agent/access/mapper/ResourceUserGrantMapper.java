package org.example.ai.agent.access.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.example.ai.agent.access.entity.ResourceUserGrant;

/**
 * 可执行资源人员授权Mapper。
 *
 * 当前只有简单单表操作，不需要Mapper XML。
 */
@Mapper
public interface ResourceUserGrantMapper
        extends BaseMapper<ResourceUserGrant> {
}
