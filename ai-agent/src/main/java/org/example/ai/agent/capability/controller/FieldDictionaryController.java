package org.example.ai.agent.capability.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.FieldDictionarySaveDTO;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.service.FieldDictionaryService;
import org.example.ai.agent.common.result.Result;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * AI 字段字典管理接口。
 *
 * 用来解释业务接口返回字段，让 Agent 和大模型理解字段含义。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/dictionaries")
public class FieldDictionaryController {

    private final FieldDictionaryService fieldDictionaryService;

    /**
     * 根据能力编码查询字段字典列表。
     * 示例：
     * GET
     */
    @GetMapping("/pageList")
    public Result<?> pageList(Page<FieldDictionary> page, @RequestParam String capabilityCode) {
        return Result.success(fieldDictionaryService.listByCapabilityCode(page, capabilityCode));
    }

    /**
     * 新增或修改字段字典。
     */
    @PostMapping("/save")
    public Result<Boolean> save(@RequestBody FieldDictionarySaveDTO dto) {
        return Result.success(fieldDictionaryService.saveField(dto));
    }

    /**
     * 删除字段字典。
     *
     * 字段字典可以物理删除；能力定义不要物理删除。
     */
    @PostMapping("/{id}/delete")
    public Result<Boolean> delete(@PathVariable Long id) {
        return Result.success(fieldDictionaryService.removeField(id));
    }
}