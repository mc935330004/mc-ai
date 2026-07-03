package org.example.airag.modules.knowledgebase.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.airag.common.result.Result;
import org.example.airag.modules.knowledgebase.dto.KnowledgeDocumentUploadRequest;
import org.example.airag.modules.knowledgebase.dto.KnowledgeDocumentUploadResponse;
import org.example.airag.modules.knowledgebase.service.KnowledgeDocumentUploadService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge/documents")
@RequiredArgsConstructor
public class KnowledgeDocumentUploadController {

    private final KnowledgeDocumentUploadService uploadService;

    /**
     * 上传企业知识文档。
     *
     * 请求类型：multipart/form-data
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocumentUploadResponse> upload(
            @Valid @ModelAttribute KnowledgeDocumentUploadRequest request
    ) {
        return Result.success(uploadService.upload(request));
    }

}
