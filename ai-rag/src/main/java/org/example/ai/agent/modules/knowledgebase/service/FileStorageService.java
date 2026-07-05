package org.example.ai.agent.modules.knowledgebase.service;

import org.springframework.web.multipart.MultipartFile;

public interface FileStorageService {

    /**
     * 保存知识库文件
     * @param file
     * @return
     */
    String saveKnowledgeBase(MultipartFile file);

    /**
     * 下载知识库文件
     * @param storageKey
     * @return
     */
    byte[] downloadFile(String storageKey);

    /**
     * 删除知识库文件
     * @param storageKey
     */
    void deleteFile(String storageKey);
}
