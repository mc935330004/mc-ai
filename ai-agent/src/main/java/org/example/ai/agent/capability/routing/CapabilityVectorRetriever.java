package org.example.ai.agent.capability.routing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 从 PGVector 中召回语义相关业务能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(VectorStore.class)
public class CapabilityVectorRetriever {

    private final VectorStore vectorStore;
    private final CapabilityRoutingProperties properties;

    public List<CapabilityVectorHit> retrieve(
            String userQuestion) {

        if (!StringUtils.hasText(userQuestion)) {
            return List.of();
        }

        try {
            SearchRequest.Builder request =
                    SearchRequest.builder().query(userQuestion.trim())
                            .topK( Math.max(properties.getVectorTopK(),1) )
                            .filterExpression(
                                    "document_type == 'CAPABILITY'"
                            );

            if (properties.getMinVectorScore() > 0D) {
                request.similarityThreshold(
                        properties.getMinVectorScore()
                );
            }

            List<Document> documents = vectorStore.similaritySearch( request.build() );

            return documents.stream()
                    .map(this::toHit)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception exception) {
            /*
             * 向量服务故障时降级为关键词召回，
             * 不能让整个 Agent 无法使用。
             */
            log.warn(
                    "能力向量召回失败，将降级为关键词召回: {}",
                    exception.getMessage()
            );

            return List.of();
        }
    }

    private CapabilityVectorHit toHit(
            Document document) {

        if (document == null || document.getMetadata() == null) {
            return null;
        }

        Object codeValue =
                document.getMetadata()
                        .get("capability_code");

        if (codeValue == null) {
            return null;
        }

        double score =document.getScore() == null
                        ? 0D
                        : document.getScore();

        return new CapabilityVectorHit(
                String.valueOf(codeValue),
                score
        );
    }
}