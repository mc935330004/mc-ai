package org.example.ai.agent.capability.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.BusinessSystemSaveDTO;
import org.example.ai.agent.capability.entity.BusinessSystem;
import org.example.ai.agent.capability.service.BusinessSystemService;
import org.example.ai.agent.common.result.Result;
import org.springframework.web.bind.annotation.*;

/**
 * AI 业务系统管理接口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/business")
public class BusinessSystemController {

    private final BusinessSystemService businessSystemService;

    /**
     * 分页查询业务系统。
     */
    @GetMapping("/pageList")
    public Result<Page<BusinessSystem>> pageList(Page<BusinessSystem> page,
                                                 @RequestParam( value = "keyword",required = false) String keyword,
                                                 @RequestParam(value = "enabled", required = false) Integer enabled) {
        return Result.success(businessSystemService.pageSystems(page, keyword, enabled ));
    }

    /**
     * 查询业务系统详情。
     */
    @GetMapping("/detail/{id}")
    public Result<BusinessSystem> detail(@PathVariable Long id) {
        return Result.success(businessSystemService.getById(id));
    }

    /**
     * 新增或修改业务系统。
     */
    @PostMapping("/save")
    public Result<Boolean> save( @RequestBody BusinessSystemSaveDTO dto ) {
        return Result.success( "保存成功",businessSystemService.saveSystem(dto));
    }

    /**
     * 启用业务系统。 1启用 0停用
     */
    @PostMapping("/{id}/enableOrDisable")
    public Result<Boolean> enableOrDisable(@PathVariable Long id,
                                  @RequestParam( value = "enabled") Integer enabled) {
        return Result.success(businessSystemService.updateEnabled(id, enabled));
    }
}