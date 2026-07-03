package org.example.airag.modules.knowledgebase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.example.airag.common.result.Result;
import org.example.airag.modules.knowledgebase.entity.KnowledgeDocumentVersion;
import org.example.airag.modules.knowledgebase.service.KnowledgeDocumentVersionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/knowledge/versions")
@RequiredArgsConstructor
public class KnowledgeDocumentVersionController {

    private final KnowledgeDocumentVersionService versionService;


    /**
     * 发布文档版本
     * @param documentId
     * @param versionId
     * @return
     */
    @PostMapping("/{documentId}/versions/{versionId}/publish")
    public Result<Void> publishVersion( @PathVariable Long documentId,@PathVariable Long versionId) {
        versionService.publishVersion(documentId, versionId);
        return Result.success("文档版本发布成功");
    }

    /**
     * 重新向量化文档版本
     * @param documentId
     * @param versionId
     * @return
     */
    @PostMapping("/{documentId}/versions/{versionId}/revectorize")
    public Result<Void> revectorizeVersion(@PathVariable Long documentId,@PathVariable Long versionId) {
        versionService.revectorizeVersion(documentId, versionId);
        return Result.success("文档版本重新向量化任务已创建");
    }
}
