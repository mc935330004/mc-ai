package org.example.ai.agent.modules.knowledgebase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeChunkService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge/chunks")
@RequiredArgsConstructor
public class KnowledgeChunkController {

    private final KnowledgeChunkService chunkService;

    /**
     * 按文档版本查询切片列表。
     *
     * 用于管理端查看某个版本实际参与 RAG 检索的文本片段。
     */
    @GetMapping("/page")
    public Result<Page<KnowledgeChunk>> page(Page<KnowledgeChunk> page,
                                             @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.success(chunkService.findChunksByDocumentVersionId(page, keyword));
    }


    /**
     * 启用或禁用切片。
     *
     * enabled=false 后，该切片后续不应参与正式检索。
     * 注意：当前只改 MySQL 状态，下一步再让查询服务过滤 enabled chunk。
     */
    @GetMapping("/enabled")
    public Result<?> updateEnabled(@RequestParam("id") Long id,
                                   @RequestParam("enabled") Integer enabled) {
        chunkService.updateEnabled(id, enabled);
        return Result.success("切片状态更新成功");
    }
}
