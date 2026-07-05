package org.example.ai.agent.modules.knowledgebase.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeCategory;
import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeCategoryMapper;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeCategoryService;
import org.example.ai.agent.modules.knowledgebase.vo.KnowledgeCategoryTreeVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class KnowledgeCategoryServiceImpl  extends ServiceImpl<KnowledgeCategoryMapper, KnowledgeCategory>
        implements KnowledgeCategoryService {
    @Override
    public Page<KnowledgeCategory> findKnowledgeCategoryPageList(Page<KnowledgeCategory> page, String keyword) {
        return baseMapper.findKnowledgeCategoryPageList(page, keyword);
    }

    @Override
    public KnowledgeCategory getKnowledgeCategoryById(Long id) {
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Boolean> saveOrUpdateKnowledgeCategory(KnowledgeCategory knowledgeCategory) {
        KnowledgeCategory one = this.lambdaQuery()
                .ne(ObjectUtil.isNotEmpty(knowledgeCategory.getId()), KnowledgeCategory::getId, knowledgeCategory.getId())
                .eq(KnowledgeCategory::getCode, knowledgeCategory.getCode()).one();
        if(ObjectUtil.isNotEmpty(one)){
            return Result.error("分类编码已存在");
        }
        return Result.success( "保存成功",saveOrUpdate(knowledgeCategory));
    }

    @Override
    public Boolean removeKnowledgeCategoryByIds(String ids) {
        return this.lambdaUpdate()
                .in(KnowledgeCategory::getId, StrUtil.split(ids, ','))
                .remove();
    }

    @Override
    public List<KnowledgeCategory> getKnowledgeCategoryParentList() {
        return this.lambdaQuery()
                .eq(KnowledgeCategory::getDelFlag,0)
                .eq(KnowledgeCategory::getParentId,0).list();
    }


    @Override
    public List<KnowledgeCategoryTreeVO> getKnowledgeCategoryTree() {
        // 获取所有分类
        List<KnowledgeCategory> categories = this.lambdaQuery()
                .eq(KnowledgeCategory::getDelFlag, 0)
                .orderByAsc(KnowledgeCategory::getSortOrder)
                .orderByAsc(KnowledgeCategory::getId)
                .list();
        Map<Long, KnowledgeCategoryTreeVO> nodeMap = new LinkedHashMap<>();
        for (KnowledgeCategory category : categories) {
            nodeMap.put(category.getId(), toTreeVO(category));
        }

        List<KnowledgeCategoryTreeVO> roots = new ArrayList<>();
        for (KnowledgeCategory category : categories) {
            KnowledgeCategoryTreeVO node = nodeMap.get(category.getId());
            Long parentId = category.getParentId();
            if (parentId == null || parentId == 0 || !nodeMap.containsKey(parentId)) {
                roots.add(node);
                continue;
            }
            nodeMap.get(parentId).getChildren().add(node);
        }
        return roots;
    }

    private KnowledgeCategoryTreeVO toTreeVO(KnowledgeCategory category) {
        KnowledgeCategoryTreeVO vo = new KnowledgeCategoryTreeVO();
        vo.setId(category.getId());
        vo.setParentId(category.getParentId());
        vo.setName(category.getName());
        vo.setCode(category.getCode());
        vo.setSortOrder(category.getSortOrder());
        vo.setEnabled(category.getEnabled());
        return vo;
    }

}
