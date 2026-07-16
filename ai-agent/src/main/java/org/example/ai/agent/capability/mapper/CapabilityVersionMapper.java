package org.example.ai.agent.capability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.capability.entity.CapabilityVersion;

@Mapper
public interface CapabilityVersionMapper  extends BaseMapper<CapabilityVersion> {

    /**
     * 查询能力最后一个版本。
     *
     * 调用方已经锁定能力定义行，因此这里不再单独加锁。
     */
    CapabilityVersion selectLatestByCapabilityId( @Param("capabilityId") Long capabilityId );

    /**
     * 将当前 ACTIVE 版本退役。
     */
    int retireActiveVersions( @Param("capabilityId") Long capabilityId );
}