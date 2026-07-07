package org.example.ai.agent.capability.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.capability.dto.FieldDictionarySaveDTO;
import org.example.ai.agent.capability.entity.FieldDictionary;

import java.util.List;

/**
 * AI 字段字典 Service。
 */
public interface FieldDictionaryService extends IService<FieldDictionary> {

    /**
     * 查询某个能力的字段字典。
     */
    Page<FieldDictionary> listByCapabilityCode(Page<FieldDictionary>page,String capabilityCode);

    /**
     * 新增或修改字段字典。
     */
    Boolean saveField(FieldDictionarySaveDTO dto);

    /**
     * 删除字段字典。
     */
    Boolean removeField(Long id);
}