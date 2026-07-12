package org.example.ai.agent.capability.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.capability.dto.BusinessSystemSaveDTO;
import org.example.ai.agent.capability.entity.BusinessSystem;

/**
 * 业务系统管理服务。
 */
public interface BusinessSystemService extends IService<BusinessSystem> {

    /**
     * 分页查询业务系统。
     */
    Page<BusinessSystem> pageSystems(Page<BusinessSystem> page, String keyword, Integer enabled);

    /**
     * 新增或修改业务系统。
     */
    Boolean saveSystem(BusinessSystemSaveDTO dto);

    /**
     * 修改启用状态。
     */
    Boolean updateEnabled(Long id, Integer enabled);

    /**
     * 根据系统编码查询启用的业务系统。
     */
    BusinessSystem getEnabledByCode(String systemCode);
}