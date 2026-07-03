package org.example.airag.modules.knowledgebase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.airag.common.result.Result;
import org.example.airag.modules.knowledgebase.entity.KnowledgeCategory;
import org.example.airag.modules.knowledgebase.service.KnowledgeCategoryService;
import org.example.airag.modules.knowledgebase.vo.KnowledgeCategoryTreeVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/categories")
@RequiredArgsConstructor
public class KnowledgeCategoryController {

    private final KnowledgeCategoryService categoryService;

    /**
     * 分页查询知识分类
     */
    @GetMapping("/page")
    public Result<Page<KnowledgeCategory>> page(Page<KnowledgeCategory> page,
                                                @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.success(categoryService.findKnowledgeCategoryPageList(page,keyword));
    }
    /**
     * 获取知识分类详情
     */
    @GetMapping("/detail/{id}")
    public Result<KnowledgeCategory> detail(@PathVariable Long id) {
        return Result.success(categoryService.getKnowledgeCategoryById(id));
    }
    /**
     * 新增或修改
     */
    @PostMapping("/addOrUpdate")
    public Result<Boolean> addOrUpdate(@RequestBody KnowledgeCategory category) {
        return categoryService.saveOrUpdateKnowledgeCategory(category);
    }

    /**
     * 批量删除
     * @param ids
     * @return
     */
    @GetMapping("/batch")
    public Result<Boolean> deleteBatch(@RequestParam(value = "ids") String ids) {
        return Result.success(categoryService.removeKnowledgeCategoryByIds(ids));
    }

    /**
     * 获取分类父类列表
     */
    @GetMapping("/parentList")
    public Result<List<KnowledgeCategory>> getParentList() {
        return Result.success(categoryService.getKnowledgeCategoryParentList());
    }

    /**
     * 获取知识分类树列表
     */
    @GetMapping("/tree")
    public Result<List<KnowledgeCategoryTreeVO>> getTree() {
        return Result.success(categoryService.getKnowledgeCategoryTree());
    }

}
