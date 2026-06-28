package org.example.airag.modules.knowledgebase.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.airag.common.exception.BusinessException;
import org.example.airag.common.exception.ErrorCode;
import org.example.airag.common.file.LocalFileStorageService;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseListItemDTO;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseStatsDTO;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBase;
import org.example.airag.modules.knowledgebase.model.VectorStatus;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseListService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final LocalFileStorageService localFileStorageService;
    /**
     * 获取知识库列表，支持向量状态过滤和简单排序。
     * @ param keyword 关键字，支持模糊匹配
     * @param vectorStatus 向量化状态，例如 COMPLETED、FAILED；为空表示不过滤
     * @param sortBy 排序字段，time 表示时间，size 表示文件大小，chunks 表示切片数
     */
    public List<KnowledgeBaseListItemDTO> listKnowledgeBases(String vectorStatus, String sortBy,String keyword) {
        // 查询知识库元数据
        List<KnowledgeBase> knowledgeBases = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getDelFlag, 0)
                .eq(StringUtils.hasText(vectorStatus), KnowledgeBase::getVectorStatus, normalizeStatus(vectorStatus))
                .and(!StringUtils.hasText(keyword),wrapper -> wrapper
                        .like(KnowledgeBase::getName, keyword.trim())
                        .or()
                        .like(KnowledgeBase::getOriginalFilename, keyword.trim())
                        .or()
                        .like(KnowledgeBase::getCategory, keyword.trim()))
                .orderByDesc(KnowledgeBase::getCreatedAt)
                .list();
        return sortKnowledgeBases(knowledgeBases, sortBy).stream()
                .map(this::toListItemDTO)
                .toList();
    }

    /**
     * 根据 ID 获取知识库详情。
     */
    public Optional<KnowledgeBaseListItemDTO> getKnowledgeBase(Long id) {
        KnowledgeBase knowledgeBase = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getDelFlag, 0)
                .one();

        return Optional.ofNullable(knowledgeBase).map(this::toListItemDTO);
    }
    /**
     * 规范化状态参数，和数据库里的 COMPLETED、FAILED 等状态保持一致。
     */
    private String normalizeStatus(String vectorStatus) {
        return StringUtils.hasText(vectorStatus) ? vectorStatus.trim().toUpperCase() : null;
    }

    /**
     * 简单排序；先只做真正用得上的字段。
     */
    private List<KnowledgeBase> sortKnowledgeBases(List<KnowledgeBase> knowledgeBases, String sortBy) {
        if (!StringUtils.hasText(sortBy) || "time".equalsIgnoreCase(sortBy)) {
            return knowledgeBases;
        }

        return switch (sortBy.toLowerCase()) {
            case "size" -> knowledgeBases.stream()
                    .sorted(Comparator.comparing(KnowledgeBase::getFileSize,
                            Comparator.nullsLast(Long::compareTo)).reversed())
                    .toList();
            case "chunks" -> knowledgeBases.stream()
                    .sorted(Comparator.comparing(KnowledgeBase::getChunkCount,
                            Comparator.nullsLast(Integer::compareTo)).reversed())
                    .toList();
            default -> knowledgeBases;
        };
    }
    /**
     * 把实体转换成列表 DTO。
     */
    private KnowledgeBaseListItemDTO toListItemDTO(KnowledgeBase kb) {
        return new KnowledgeBaseListItemDTO(
                kb.getId(),
                kb.getName(),
                kb.getCategory(),
                kb.getOriginalFilename(),
                kb.getFileSize(),
                kb.getContentType(),
                kb.getCreatedAt(),
                kb.getVectorStatus(),
                kb.getVectorError(),
                kb.getChunkCount()
        );
    }

    /**
     * 获取知识库原始文件信息。
     *
     * <p>下载接口需要文件名、Content-Type、存储路径，所以这里返回实体。</p>
     */
    public KnowledgeBase getEntityForDownload(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库ID不能为空");
        }
        KnowledgeBase kb = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getDelFlag, 0)
                .one();
        if (kb == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在");
        }
        return kb;
    }

    public byte[] downloadFile(Long id) {
        KnowledgeBase kb = getEntityForDownload(id);

        if (!StringUtils.hasText(kb.getStoragePath())) {
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件存储信息不存在");
        }

        // 复用本地文件存储服务，避免 Controller 直接关心磁盘路径。
        log.info("下载知识库文件: kbId={}, filename={}", kb.getId(), kb.getOriginalFilename());

        return localFileStorageService.downloadFile(kb.getStoragePath());
    }

    /**
     * 获取所有知识库分类。
     *
     * <p>当前不单独建 category 表，直接从 knowledge_base.category 字段去重得到。</p>
     */
    public List<String> getAllCategories() {
        return knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getDelFlag, 0)
                .isNotNull(KnowledgeBase::getCategory)
                .list()
                .stream()
                .map(KnowledgeBase::getCategory)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 根据分类查询知识库列表。
     *
     * @param category 分类名称；为空时查询未分类知识库
     */
    public List<KnowledgeBaseListItemDTO> listByCategory(String category) {
        List<KnowledgeBase> knowledgeBases;

        if (StringUtils.hasText(category)) {
            // 查询指定分类下的知识库。
            knowledgeBases = knowledgeBaseService.lambdaQuery()
                    .eq(KnowledgeBase::getDelFlag, 0)
                    .eq(KnowledgeBase::getCategory, category.trim())
                    .orderByDesc(KnowledgeBase::getCreatedAt)
                    .list();
        } else {
            // 查询未分类知识库：category 为 null 或空字符串。
            knowledgeBases = knowledgeBaseService.lambdaQuery()
                    .eq(KnowledgeBase::getDelFlag, 0)
                    .and(wrapper -> wrapper
                            .isNull(KnowledgeBase::getCategory)
                            .or()
                            .eq(KnowledgeBase::getCategory, ""))
                    .orderByDesc(KnowledgeBase::getCreatedAt)
                    .list();
        }

        return knowledgeBases.stream()
                .map(this::toListItemDTO)
                .toList();
    }

    /**
     * 更新知识库分类。
     *
     * <p>category 为空时表示清空分类，归为未分类。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateCategory(Long id, String category) {
        if (id == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库ID不能为空");
        }
        KnowledgeBase kb = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getDelFlag, 0)
                .one();
        if (kb == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在");
        }

        // 空字符串统一保存为 null，避免数据库里同时出现 null 和 "" 两种未分类状态。
        String normalizedCategory = StringUtils.hasText(category) ? category.trim() : null;

        kb.setCategory(normalizedCategory);
        kb.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseService.updateById(kb);
        log.info("更新知识库分类成功: kbId={}, category={}", id, normalizedCategory);
    }

    /**
     * 获取知识库统计信息。
     *
     * <p>当前项目没有 accessCount/questionCount 字段，所以先统计基础管理数据。
     * 后面做聊天会话历史时，再补提问次数统计。</p>
     */
    public KnowledgeBaseStatsDTO getStatistics() {
// ponytail: 先一次查出当前未删除知识库，在内存聚合；数据量很大时再改 SQL 聚合。
        List<KnowledgeBase> knowledgeBases = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getDelFlag, 0)
                .list();

        long totalCount = knowledgeBases.size();

        long completedCount = countByVectorStatus(knowledgeBases, VectorStatus.COMPLETED);
        long processingCount = countByVectorStatus(knowledgeBases, VectorStatus.PROCESSING);
        long failedCount = countByVectorStatus(knowledgeBases, VectorStatus.FAILED);
        long pendingCount = countByVectorStatus(knowledgeBases, VectorStatus.PENDING);

        long totalFileSize = knowledgeBases.stream()
                .mapToLong(kb -> kb.getFileSize() == null ? 0L : kb.getFileSize())
                .sum();
        long totalChunkCount = knowledgeBases.stream()
                .mapToLong(kb -> kb.getChunkCount() == null ? 0L : kb.getChunkCount())
                .sum();
        long categoryCount = knowledgeBases.stream()
                .map(KnowledgeBase::getCategory)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .count();
        return new KnowledgeBaseStatsDTO(
                totalCount,
                completedCount,
                processingCount,
                failedCount,
                pendingCount,
                totalFileSize,
                totalChunkCount,
                categoryCount
        );
    }
    /**
     * 按向量化状态统计知识库数量。
     */
    private long countByVectorStatus(List<KnowledgeBase> knowledgeBases, VectorStatus status) {
        return knowledgeBases.stream()
                .filter(kb -> status.name().equals(kb.getVectorStatus()))
                .count();
    }
}
