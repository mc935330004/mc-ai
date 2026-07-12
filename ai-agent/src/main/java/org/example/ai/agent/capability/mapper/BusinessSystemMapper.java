package org.example.ai.agent.capability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.capability.entity.BusinessSystem;

/**
 * 业务系统 Mapper。
 *
 * 当前阶段都是简单单表操作，不需要 Mapper XML。
 */
@Mapper
public interface BusinessSystemMapper extends BaseMapper<BusinessSystem> {
    /**
     * 分页查询业务系统。
     */
    Page<BusinessSystem> findBusinessSystemPageList(Page<BusinessSystem> page,
                                                    @Param("keyword") String keyword,
                                                    @Param("enabled") Integer enabled);
}