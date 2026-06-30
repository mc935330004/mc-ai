package org.example.airag.modules.knowledgebase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.airag.common.exception.ErrorCode;
import org.example.airag.common.result.Result;
import org.example.airag.modules.knowledgebase.dto.*;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBase;
import org.example.airag.modules.knowledgebase.model.QueryRequest;
import org.example.airag.modules.knowledgebase.model.QueryResponse;
import org.example.airag.modules.knowledgebase.service.FileStorageService;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseService;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseUploadService;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.example.airag.modules.knowledgebase.service.impl.KnowledgeBaseDeleteService;
import org.example.airag.modules.knowledgebase.service.impl.KnowledgeBaseQueryService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledgebase")
@RequiredArgsConstructor
@Tag(name = "知识库管理", description = "知识库上传、下载、查询、分类与向量化")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseUploadService uploadService;
    private final KnowledgeBaseQueryService queryService;
    private final KnowledgeBaseDeleteService deleteService;
    private final FileStorageService fileStorageService;
    private final KnowledgeBaseVectorTaskService vectorTaskService;
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
     * 知识库流式查询
     * @param request
     * @return
     */
    @PostMapping(value = "/query/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> queryKnowledgeBaseStream(@Valid @RequestBody QueryRequest request) {
        return queryService.queryKnowledgeBaseStream(request);
    }

    /**
     * 获取知识库列表。
     */
    @GetMapping("/list")
    public Result getAllKnowledgeBases(Page pag, KnowledgeBaseSerDTO dto) {
        return Result.success(knowledgeBaseService.listKnowledgeBases(pag, dto));
    }

    /**
     * 获取知识库详情。
     */
    @GetMapping("/detail/{id}")
    public Result getKnowledgeBase(@PathVariable Long id) {
        return Result.success(knowledgeBaseService.getKnowledgeBase(id));
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
        KnowledgeBase entity= Optional.ofNullable(knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getDelFlag, BigDecimal.ZERO).one()).orElseThrow(() ->
                new RuntimeException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getMessage()));
        byte[] fileContent = fileStorageService.downloadFile(entity.getStoragePath());
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
        return Result.success(knowledgeBaseService.getAllCategories());
    }

    /**
     * 更新知识库分类。
     *
     * <p>请求体示例：{"category":"技术文档"}；传空字符串表示清空分类。</p>
     */
    @PostMapping("/updateCategory")
    public Result<Void> updateCategory(@RequestBody KnowledgeBase  kb) {
        knowledgeBaseService.updateKnowledgeBase(kb);
        return Result.success("更新知识库分类成功！");
    }

    /**
     * 获取知识库统计信息。
     */
    @GetMapping("/stats")
    public Result<KnowledgeBaseStatsDTO> getStatistics() {
        return Result.success(knowledgeBaseService.getStatistics());
    }

    /**
     * 获取向量化状态
     * @param id
     * @return
     */
    @GetMapping("/getVectorStatus/{id}")
    public Result<VectorStatusDTO> getVectorStatus(@PathVariable Long id) {
        KnowledgeBase kb= Optional.ofNullable(knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getDelFlag, BigDecimal.ZERO).one()).orElseThrow(() ->
                new RuntimeException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getMessage()));
        return Result.success(new VectorStatusDTO(
                kb.getId(),
                kb.getVectorStatus(),
                kb.getVectorError(),
                kb.getChunkCount()
        ));
    }

    /**
     * 获取向量化任务列表
     * @param status
     * @param knowledgeBaseId
     * @return
     */
    @GetMapping("/vectorTasks")
    public Result<List<VectorTaskDTO>> listVectorTasks(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "knowledgeBaseId", required = false) Long knowledgeBaseId) {
        return Result.success(vectorTaskService.listTasks(status, knowledgeBaseId));
    }

    /**
     * 重新向量化任务
     * @param taskId
     * @return
     */
    @PostMapping("/retryVectorTask/{taskId}")
    public Result<Void> retryVectorTask(@PathVariable Long taskId) {
        vectorTaskService.retryTask(taskId);
        return Result.success("重新向量化任务成功！");
    }

    /**
     * 测试文件上传接口
     */
    @PostMapping("/upload/test")
    public Result uploadTest(MultipartFile file){
        fileStorageService.saveKnowledgeBase(file);
        return Result.success("上传成功！");
    }
}
