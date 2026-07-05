package org.example.ai.agent.modules.knowledgebase.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeChunkMapper;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeChunkService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class KnowledgeChunkServiceImpl extends ServiceImpl<KnowledgeChunkMapper, KnowledgeChunk>
        implements KnowledgeChunkService {
    @Override
    public Page<KnowledgeChunk> findChunksByDocumentVersionId(Page<KnowledgeChunk>page,String keyword) {
        return baseMapper.findChunksByDocumentVersionId(page,keyword);
    }

    @Override
    public void updateEnabled(Long id, Integer enabled) {
        KnowledgeChunk chunk = Optional.ofNullable(this.lambdaQuery()
                        .eq(KnowledgeChunk::getId,id)
                        .eq(KnowledgeChunk::getDelFlag,0).one())
                .orElseThrow(() -> new RuntimeException("切片不存在"));
        chunk.setEnabled(enabled);
        chunk.setUpdatedAt(LocalDateTime.now());
        this.updateById(chunk);
    }
}
