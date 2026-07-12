package org.example.ai.agent.capability.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.ai.agent.capability.dto.BusinessSystemSaveDTO;
import org.example.ai.agent.capability.entity.BusinessSystem;
import org.example.ai.agent.capability.mapper.BusinessSystemMapper;
import org.example.ai.agent.capability.service.BusinessSystemService;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 业务系统管理服务实现。
 */
@Service
public class BusinessSystemServiceImpl extends ServiceImpl<BusinessSystemMapper, BusinessSystem>
        implements BusinessSystemService {

    /**
     * 支持按系统编码或系统名称搜索。
     */
    @Override
    public Page<BusinessSystem> pageSystems(Page<BusinessSystem> page,String keyword, Integer enabled) {
        return baseMapper.findBusinessSystemPageList(page, keyword, enabled);
    }

    /**
     * 新增或修改业务系统。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean saveSystem(BusinessSystemSaveDTO dto) {
        String systemCode = normalizeSystemCode(dto.getSystemCode());
        String systemName = dto.getSystemName().trim();
        String baseUrl = normalizeBaseUrl(dto.getBaseUrl());
        String authType = normalizeAuthType(dto.getAuthType());

        // 系统编码必须保持唯一。
        long duplicateCount = lambdaQuery().eq(BusinessSystem::getSystemCode, systemCode)
                .ne(dto.getId() != null, BusinessSystem::getId, dto.getId())
                .count();

        if (duplicateCount > 0) {
            throw new BusinessException( 400,"业务系统编码已存在：" + systemCode);
        }
        BusinessSystem entity = new BusinessSystem();
        BeanUtils.copyProperties(dto, entity);

        entity.setSystemCode(systemCode);
        entity.setSystemName(systemName);
        entity.setBaseUrl(baseUrl);
        entity.setOpenapiUrl(trimToNull(dto.getOpenapiUrl()));
        entity.setAuthType(authType);
        entity.setUpdatedAt(LocalDateTime.now());
        if (entity.getEnabled() == null) {
            entity.setEnabled(1);
        }
        if (entity.getId() == null) {
            entity.setCreatedAt(LocalDateTime.now());
        } else if (getById(entity.getId()) == null) {
            throw new BusinessException( 404,"业务系统不存在：" + entity.getId() );
        }
        return saveOrUpdate(entity);
    }

    /**
     * 启用或停用业务系统。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateEnabled(Long id, Integer enabled) {
        if (id == null) {
            throw new BusinessException(400, "业务系统ID不能为空");
        }
        if (!List.of(0, 1).contains(enabled)) {
            throw new BusinessException(400, "enabled 只允许为0或1");
        }

        BusinessSystem existing = getById(id);
        if (existing == null) {
            throw new BusinessException(404,"业务系统不存在：" + id);
        }
        BusinessSystem update = new BusinessSystem();
        update.setId(id);
        update.setEnabled(enabled);
        update.setUpdatedAt(LocalDateTime.now());

        return updateById(update);
    }

    /**
     * 根据系统编码查询启用的业务系统。
     */
    @Override
    public BusinessSystem getEnabledByCode(String systemCode) {
        if (!StringUtils.hasText(systemCode)) {
            return null;
        }
        return lambdaQuery().eq( BusinessSystem::getSystemCode, normalizeSystemCode(systemCode))
                .eq(BusinessSystem::getEnabled, 1)
                .one();
    }

    /**
     * 统一将系统编码转换为大写。
     */
    private String normalizeSystemCode(String systemCode) {
        if (!StringUtils.hasText(systemCode)) {
            throw new BusinessException(400, "业务系统编码不能为空");
        }
        return systemCode.trim().toUpperCase();
    }

    /**
     * 删除基础地址末尾的斜杠，避免后续地址拼接出现双斜杠。
     */
    private String normalizeBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new BusinessException(400, "业务系统基础地址不能为空");
        }

        String value = baseUrl.trim();

        if (!value.startsWith("http://")&& !value.startsWith("https://")) {
            throw new BusinessException( 400,"业务系统基础地址必须以 http:// 或 https:// 开头");
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 标准化认证方式。
     */
    private String normalizeAuthType(String authType) {
        String value = StringUtils.hasText(authType)
                ? authType.trim().toUpperCase()
                : "FORWARD";

        if (!List.of("FORWARD", "NONE").contains(value)) {
            throw new BusinessException(
                    400,
                    "authType 只允许为 FORWARD 或 NONE"
            );
        }

        return value;
    }

    /**
     * 空白字符串转换为 null。
     */
    private String trimToNull(String value) {
        return StringUtils.hasText(value)
                ? value.trim()
                : null;
    }
}