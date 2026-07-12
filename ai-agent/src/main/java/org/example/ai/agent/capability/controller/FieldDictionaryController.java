package org.example.ai.agent.capability.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.dto.*;
import org.example.ai.agent.capability.entity.FieldDictionary;
import org.example.ai.agent.capability.service.FieldDictionaryService;
import org.example.ai.agent.capability.service.FieldSemanticService;
import org.example.ai.agent.capability.vo.FieldDictionaryBatchSaveResultVO;
import org.example.ai.agent.capability.vo.FieldDictionaryGenerateResultVO;
import org.example.ai.agent.capability.vo.FieldSemanticSuggestionVO;
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
    private final FieldSemanticService fieldSemanticService;
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
    /**
     * 根据 ID 查询字段字典详情。
     */
    @GetMapping("/detail/{id}")
    public Result<FieldDictionary> detail(@PathVariable Long id) {
        return Result.success(fieldDictionaryService.detail(id));
    }

    /**
     * 根据能力 outputSchemaJson 批量生成字段字典。
     *
     * 已存在的 fieldPath 不会重复创建。
     */
    @PostMapping("/generateFromOutputSchema")
    public Result<List<FieldDictionaryGenerateResultVO>> generateFromOutputSchema( @RequestBody FieldDictionarySchemaGenerateDTO dto) {
        return Result.success(fieldDictionaryService .batchGenerateFromOutputSchema(dto.getCapabilityCodes()));
    }

    /**
     * 保存用户从真实响应中勾选的字段。
     *
     * 已存在的字段路径不会被覆盖。
     */
    @PostMapping("/confirmGeneratedFields")
    public Result<FieldDictionaryBatchSaveResultVO> confirmGeneratedFields(@RequestBody FieldDictionaryBatchConfirmDTO dto) {
        return Result.success(fieldDictionaryService.confirmGeneratedFields(dto) );
    }
    /**
     * 批量生成字段中文名和业务含义建议。
     *
     * 只返回建议，不修改数据库。
     */
    @PostMapping("/semantic/suggest")
    public Result<List<FieldSemanticSuggestionVO>> suggest(@RequestBody FieldSemanticSuggestDTO dto) {
        return Result.success(fieldSemanticService.suggest(dto));
    }

    /**
     * 保存用户确认后的字段语义。
     */
    @PostMapping("/semantic/confirm")
    public Result<Integer> confirm(@RequestBody FieldSemanticConfirmDTO dto ) {
        return Result.success(fieldSemanticService.confirm(dto));
    }
}