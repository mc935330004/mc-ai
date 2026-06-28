package org.example.airag.modules.knowledgebase.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 知识库上传服务
 */
public interface KnowledgeBaseUploadService {

    /**
     * 上传知识库文件
     *
     * @param file 文件
     * @param name 文件名
     * @param category 文件分类
     * @return 文件信息
     */
    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category);

    /**
     * 重新向量化知识库。
     *
     * @param id 知识库 ID
     */
    void revectorize(Long id);
}
