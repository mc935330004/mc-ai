package org.example.ai.agent.capability.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.ai.agent.capability.dto.FieldDictionarySaveDTO;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.mapper.FieldDictionaryMapper;
import org.example.ai.agent.capability.service.FieldDictionaryService;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * AI 字段字典 Service 实现。
 */
@Service
public class FieldDictionaryServiceImpl
        extends ServiceImpl<FieldDictionaryMapper, FieldDictionary>
        implements FieldDictionaryService {

    @Override
    public Page<FieldDictionary> listByCapabilityCode(Page<FieldDictionary>page,String capabilityCode) {
        return baseMapper.listByCapabilityCode(page, capabilityCode);
    }

    @Override
    public Boolean saveField(FieldDictionarySaveDTO dto) {
        boolean exists = lambdaQuery()
                .eq(FieldDictionary::getCapabilityCode, dto.getCapabilityCode())
                .eq(FieldDictionary::getFieldPath, dto.getFieldPath())
                .ne(dto.getId() != null, FieldDictionary::getId, dto.getId())
                .count() > 0;
        if (exists) {
            throw new BusinessException(400, "同一能力下字段路径已存在：" + dto.getFieldPath());
        }
        FieldDictionary entity = new FieldDictionary();
        BeanUtils.copyProperties(dto, entity);
        if (entity.getSearchable() == null) {
            entity.setSearchable(0);
        }
        if (entity.getAggregatable() == null) {
            entity.setAggregatable(0);
        }
        return saveOrUpdate(entity);
    }

    @Override
    public Boolean removeField(Long id) {
        if (getById(id) == null) {
            throw new BusinessException(404, "字段字典不存在：" + id);
        }
        return removeById(id);
    }
}