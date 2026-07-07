package org.example.ai.agent.capability.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.capability.dto.FieldDictionarySaveDTO;
import org.example.ai.agent.capability.entity.FieldDictionary;

import java.util.List;

/**
 * AI 字段字典 Mapper。
 */
@Mapper
public interface FieldDictionaryMapper extends BaseMapper<FieldDictionary> {

    /**
     * 查询某个能力的字段字典。
     */
    Page<FieldDictionary> listByCapabilityCode(Page<FieldDictionary>page,
                                               @Param("capabilityCode") String capabilityCode);

}