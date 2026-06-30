package org.example.airag.modules.knowledgebase.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.airag.common.exception.ErrorCode;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseListItemDTO;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseSerDTO;
import org.example.airag.modules.knowledgebase.dto.KnowledgeBaseStatsDTO;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBase;
import org.example.airag.modules.knowledgebase.mapper.KnowledgeBaseMapper;
import org.example.airag.modules.knowledgebase.model.VectorStatus;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 知识库文件 Service 实现类
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements KnowledgeBaseService {

    @Override
    public Page<KnowledgeBaseListItemDTO> listKnowledgeBases(Page<KnowledgeBaseListItemDTO> page, KnowledgeBaseSerDTO dto) {
        return baseMapper.listKnowledgeBases(page, dto);
    }

    @Override
    public KnowledgeBaseListItemDTO getKnowledgeBase(Long id) {
        KnowledgeBase knowledgeBase = this.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getDelFlag, 0)
                .one();
        return Optional.ofNullable(knowledgeBase).map(this::toListItemDTO).orElseThrow(() ->
                new RuntimeException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getMessage()));
    }

    @Override
    public List<String> getAllCategories() {
        return this.lambdaQuery()
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

    @Override
    public void updateKnowledgeBase(KnowledgeBase base) {
        Optional.ofNullable(this.lambdaQuery()
                .eq(KnowledgeBase::getId, base.getId())
                .eq(KnowledgeBase::getDelFlag, BigDecimal.ZERO).one()).orElseThrow(() ->
                new RuntimeException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND.getMessage()));
        this.updateById(base);
        log.info("更新知识库分类成功: kbId={}, category={}", base.getId(), base.getCategory());
    }

    @Override
    public KnowledgeBaseStatsDTO getStatistics() {
        // ponytail: 先一次查出当前未删除知识库，在内存聚合；数据量很大时再改 SQL 聚合。
        List<KnowledgeBase> knowledgeBases = this.lambdaQuery()
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
     * 按向量化状态统计知识库数量。
     */
    private long countByVectorStatus(List<KnowledgeBase> knowledgeBases, VectorStatus status) {
        return knowledgeBases.stream()
                .filter(kb -> status.name().equals(kb.getVectorStatus()))
                .count();
    }
}