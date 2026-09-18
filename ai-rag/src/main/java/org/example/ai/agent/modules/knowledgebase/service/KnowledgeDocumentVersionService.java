package org.example.ai.agent.modules.knowledgebase.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocumentVersion;

/**
 * 企业知识文档版本服务。
 */
public interface KnowledgeDocumentVersionService extends IService<KnowledgeDocumentVersion> {

    /**
     * 执行指定任务领取批次的文档向量化。
     */
    void vectorizeVersion(Long taskId, Long versionId, String claimToken);

    /**
     * 保存当前领取批次的任务及版本失败状态。
     *
     * @return true表示当前领取批次更新成功，false表示领取已经失效
     */
    boolean markVectorizeFailed(Long taskId, Long versionId, String claimToken, String errorMessage);

    /**
     * 发布指定文档版本。
     */
    void publishVersion(Long documentId, Long versionId);

    /**
     * 重新向量化指定文档版本。
     */
    void revectorizeVersion(Long documentId, Long versionId);
}