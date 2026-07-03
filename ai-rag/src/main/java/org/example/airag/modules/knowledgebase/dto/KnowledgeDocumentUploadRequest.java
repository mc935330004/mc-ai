package org.example.airag.modules.knowledgebase.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

/**
 * 企业知识文档上传请求。
 *
 * 这个 DTO 用于 multipart/form-data 表单提交：
 * - file：原始文档文件
 * - title：文档标题
 * - versionNo：版本号
 * - categoryId：知识分类
 */
@Data
public class KnowledgeDocumentUploadRequest {

    /**
     * 上传的原始文件，例如 PDF、DOCX、TXT、MD。
     */
    @NotNull(message = "上传文件不能为空")
    private MultipartFile file;

    /**
     * 文档标题，例如：差旅报销制度、项目立项流程。
     */
    @NotBlank(message = "文档标题不能为空")
    private String title;

    /**
     * 文档编号，例如：HR-TRAVEL-001。
     */
    private String documentCode;

    /**
     * 所属分类 ID，对应 knowledge_category.id。
     */
    private Long categoryId;

    /**
     * 归属部门，例如：人力资源部、财务部、项目管理部。
     */
    private String ownerDept;

    /**
     * 版本号，例如：v1.0、v1.1。
     */
    @NotBlank(message = "版本号不能为空")
    private String versionNo;

    /**
     * 文档摘要，可由用户填写，后续也可以接入 AI 自动摘要。
     */
    private String summary;

    /**
     * 生效开始时间。
     */
    private LocalDateTime effectiveStartTime;

    /**
     * 生效结束时间。
     */
    private LocalDateTime effectiveEndTime;
}