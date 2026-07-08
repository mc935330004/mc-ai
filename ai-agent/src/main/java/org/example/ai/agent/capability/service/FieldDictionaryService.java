package org.example.ai.agent.capability.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.capability.dto.FieldDictionaryGenerateDTO;
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

    /**
     * 详情
     */
    FieldDictionary detail(Long id);

    /**
     * 根据接口返回 JSON 自动生成字段字典草稿。
     */
    void generateFromJson(FieldDictionaryGenerateDTO dto);

    /**
     * 保存自动生成的字段字典。
     *
     * 只保存不存在的字段路径。
     */
    Boolean saveGeneratedFields(String capabilityCode, List<FieldDictionary> fields);
}