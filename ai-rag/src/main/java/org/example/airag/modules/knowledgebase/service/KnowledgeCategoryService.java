package org.example.airag.modules.knowledgebase.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.example.airag.common.result.Result;
import org.example.airag.modules.knowledgebase.entity.KnowledgeCategory;
import org.example.airag.modules.knowledgebase.vo.KnowledgeCategoryTreeVO;

import java.util.List;

public interface KnowledgeCategoryService extends IService<KnowledgeCategory> {

    /**
     * 获取知识分类列表。
     */
    Page<KnowledgeCategory>findKnowledgeCategoryPageList(Page<KnowledgeCategory>page,String keyword);

    /**
     * 详情
     */
    KnowledgeCategory getKnowledgeCategoryById(Long id);

    /**
     * 新增或修改
     */
    Result<Boolean> saveOrUpdateKnowledgeCategory(KnowledgeCategory knowledgeCategory);

    /**
     * 批量删除
     */
    Boolean removeKnowledgeCategoryByIds(String ids);

    /**
     * 获取分类的父类列表
     */
    List<KnowledgeCategory> getKnowledgeCategoryParentList();

    /**
     *  获取分类树列表
     */
    List<KnowledgeCategoryTreeVO> getKnowledgeCategoryTree();
}
