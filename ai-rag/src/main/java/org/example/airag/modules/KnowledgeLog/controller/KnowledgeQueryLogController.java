package org.example.airag.modules.KnowledgeLog.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.airag.common.result.Result;
import org.example.airag.modules.KnowledgeLog.dto.KnowledgeQueryStatsDTO;
import org.example.airag.modules.KnowledgeLog.entity.KnowledgeQueryLog;
import org.example.airag.modules.KnowledgeLog.entity.KnowledgeQueryReference;
import org.example.airag.modules.KnowledgeLog.service.KnowledgeQueryLogService;
import org.example.airag.modules.KnowledgeLog.service.KnowledgeQueryReferenceService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge/queryLogs")
@RequiredArgsConstructor
public class KnowledgeQueryLogController {

    private final KnowledgeQueryLogService queryLogService;
    private final KnowledgeQueryReferenceService queryReferenceService;

    /**
     * 分页查询问答日志。
     * 支持按状态和问题关键词过滤。
     */
    @GetMapping("/page")
    public Result<Page<KnowledgeQueryLog>> getQueryLogPage(Page<KnowledgeQueryLog> page,
                                                           @RequestParam(value = "status", required = false) String status,
                                                           @RequestParam(value = "answer", required = false) String answer) {
        return Result.success(queryLogService.findKnowledgeQueryLogList(page,status, answer));
    }

    /**
     * 查询单条问答日志详情。
     */
    @GetMapping("/detail/{id}")
    public Result<KnowledgeQueryLog> detail(@PathVariable Long id) {
        return Result.success(queryLogService.getKnowledgeQueryLogDetail(id));
    }

    /**
     * 查询某次问答命中的引用来源。
     */
    @GetMapping("/references")
    public Result<?> getReferences(Page<KnowledgeQueryReference> page,
                                   @RequestParam("logId") Long logId) {
        return Result.success(queryReferenceService.getReferences(page,logId));
    }

    /**
     * 查询问答的统计信息。
     */
    @GetMapping("/stats")
    public Result<KnowledgeQueryStatsDTO> stats() {
        return Result.success(queryLogService.getEnterpriseQuestionStatistics());
    }
}
