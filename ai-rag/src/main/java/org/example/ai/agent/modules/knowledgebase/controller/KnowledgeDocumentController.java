package org.example.ai.agent.modules.knowledgebase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentDTO;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentOverviewDTO;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentQueryRequest;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentQueryResponse;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeBaseVectorTask;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeDocumentService;
import org.example.ai.agent.modules.knowledgebase.service.impl.KnowledgeDocumentQueryService;
import org.example.ai.agent.modules.knowledgebase.vo.KnowledgeDocumentListItemVO;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/knowledge/documents")
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService documentService;
    private final KnowledgeDocumentQueryService knowledgeDocumentQueryService;

    /**
     * 企业文档普通问答。
     */
    @PostMapping("/query")
    public Result<KnowledgeDocumentQueryResponse> query(@RequestBody KnowledgeDocumentQueryRequest request) {
        return Result.success(knowledgeDocumentQueryService.query(request));
    }

    /**
     * 企业文档流式问答，返回 text/event-stream。
     */
    @PostMapping(value = "/query/stream",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamQuery(@RequestBody KnowledgeDocumentQueryRequest request) {
        return knowledgeDocumentQueryService.streamQuery(request);
    }

    /**
     * 根据 ID 获取企业文档概览。
     */
    @GetMapping("/{id}/overview")
    public Result<KnowledgeDocumentOverviewDTO> overview(@PathVariable Long id) {
        return Result.success(documentService.overview(id));
    }

    /**
     * 企业文档分页列表。
     */
    @GetMapping("/pageList")
    public Result<Page<KnowledgeDocumentListItemVO>> pageList(Page<KnowledgeDocumentListItemVO> page,
                                                             KnowledgeDocumentDTO query) {
        return Result.success(documentService.findPageList(page, query));
    }

    /**
     * 废止文档，废止后不再参与正式问答。
     */
    @PostMapping("/{id}/deprecated")
    public Result<Void> deprecated(@PathVariable Long id) {
        documentService.deprecatedDocument(id);
        return Result.success("文档已废止");
    }

    /**
     * 归档文档，归档后不再参与正式问答。
     */
    @PostMapping("/{id}/archive")
    public Result<Void> archive(@PathVariable Long id) {
        documentService.archiveDocument(id);
        return Result.success("文档已归档");
    }

    /**
     * 恢复文档为已发布状态。
     */
    @PostMapping("/{id}/restorePublished")
    public Result<Void> restorePublished(@PathVariable Long id) {
        documentService.restorePublished(id);
        return Result.success("文档已恢复发布");
    }

    /**
     * 向量任务监控列表（分页）
     */
    @GetMapping("/vectorTaskList")
    public Result<?> vectorTaskList(Page<KnowledgeBaseVectorTask> page, @Valid KnowledgeDocumentDTO request) {
        return Result.success(documentService.findVectorTaskList(page, request));
    }
}