package org.example.airag.modules.knowledgebase.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.airag.common.file.DocumentParseService;
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
    private final DocumentParseService documentParseService;
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

    /**
     * 上传一个文件，返回解析后的前 1000 个字符。
     * 不入库、不切片、不写向量库，只测解析链路。
     */
    @PostMapping(value = "/textUpload",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> parse(@RequestParam("file") MultipartFile file) {
        String content = documentParseService.parseContent(file);

        // 避免接口返回超大文本，把结果截断一下
        String preview = content.length() > 1000
                ? content.substring(0, 1000)
                : content;

        return Result.success(preview);
    }
}
