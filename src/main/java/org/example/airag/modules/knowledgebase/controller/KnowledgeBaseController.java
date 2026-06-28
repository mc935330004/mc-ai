package org.example.airag.modules.knowledgebase.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.airag.common.result.Result;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseListItemDTO;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseStatsDTO;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBase;
import org.example.airag.modules.knowledgebase.model.QueryRequest;
import org.example.airag.modules.knowledgebase.model.QueryResponse;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseUploadService;
import org.example.airag.modules.knowledgebase.service.impl.KnowledgeBaseDeleteService;
import org.example.airag.modules.knowledgebase.service.impl.KnowledgeBaseListService;
import org.example.airag.modules.knowledgebase.service.impl.KnowledgeBaseQueryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 知识库控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledgebase")
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库上传、下载、查询、分类与向量化")
public class KnowledgeBaseController {

    private final KnowledgeBaseUploadService uploadService;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseListService listService;
    private final KnowledgeBaseDeleteService deleteService;
    /**
     * 上传知识库文件
     * @param file
     * @param name
     * @param category
     * @return
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<Map<String, Object>> uploadKnowledgeBase(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "category", required = false) String category) {
        return Result.success(uploadService.uploadKnowledgeBase(file, name, category));
    }

    /**
     * 查询知识库。
     *
     * <p>请求流程：用户问题 -> 向量检索 -> 拼接上下文 -> 大模型回答。</p>
     */
    @PostMapping("/query")
    public Result<QueryResponse> queryKnowledgeBase(@Valid @RequestBody QueryRequest request) {
        return Result.success(queryService.queryKnowledgeBase(request));
    }

    /**
     * 获取知识库列表。
     */
    @GetMapping("/list")
    public Result<List<KnowledgeBaseListItemDTO>> getAllKnowledgeBases(
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "vectorStatus", required = false) String vectorStatus,
            @RequestParam(value = "keyword", required = false) String keyword) {
        return Result.success(listService.listKnowledgeBases(vectorStatus, sortBy, keyword));
    }

    /**
     * 获取知识库详情。
     */
    @GetMapping("/detail/{id}")
    public Result<KnowledgeBaseListItemDTO> getKnowledgeBase(@PathVariable Long id) {
        return listService.getKnowledgeBase(id)
                .map(Result::success)
                .orElse(Result.error("知识库不存在"));
    }

    /**
     * 删除知识库。
     */
    @DeleteMapping("/delete/{id}")
    public Result<Void> deleteKnowledgeBase(@PathVariable Long id) {
        deleteService.deleteKnowledgeBase(id);
        return Result.success("知识库删除功能！");
    }

    /**
     * 重新向量化知识库。
     */
    @PostMapping("/revectorize/{id}")
    public Result revectorize(@PathVariable Long id) {
        uploadService.revectorize(id);
        return Result.success("重新向量化知识库成功！");
    }

    /**
     * 下载知识库原始文件。
     *
     * <p>参考 interview-guide 的下载接口：
     * Service 负责读取文件，Controller 负责设置下载响应头。</p>
     */
    @GetMapping("/download/{id}")
    public ResponseEntity<byte[]> downloadKnowledgeBase(@PathVariable Long id) {
        KnowledgeBase entity = listService.getEntityForDownload(id);
        byte[] fileContent = listService.downloadFile(id);

        String filename = StringUtils.hasText(entity.getOriginalFilename())
                ? entity.getOriginalFilename()
                : "knowledge-base-" + id;
        // URL 编码文件名，避免中文文件名下载时乱码。
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String contentType = StringUtils.hasText(entity.getContentType())
                ? entity.getContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + encodedFilename + "\"; filename*=UTF-8''" + encodedFilename
                )
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .body(fileContent);
    }

    /**
     * 获取所有知识库分类。
     */
    @GetMapping("/categories")
    public Result<List<String>> getAllCategories() {
        return Result.success(listService.getAllCategories());
    }

    /**
     * 根据分类获取知识库列表。
     *
     * <p>分类名如果是中文，前端请求时需要 URL 编码。</p>
     */
    @GetMapping("/category/{category}")
    public Result getByCategory(@PathVariable String category) {
        return Result.success(listService.listByCategory(category));
    }

    /**
     * 更新知识库分类。
     *
     * <p>请求体示例：{"category":"技术文档"}；传空字符串表示清空分类。</p>
     */
    @GetMapping("/category")
    public Result<Void> updateCategory(@RequestParam(value ="id") Long id,
                                       @RequestParam(value = "category") String category) {
        listService.updateCategory(id, category);
        return Result.success("更新知识库分类成功！");
    }

    /**
     * 获取知识库统计信息。
     */
    @GetMapping("/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(listService.getStatistics());
    }
}
