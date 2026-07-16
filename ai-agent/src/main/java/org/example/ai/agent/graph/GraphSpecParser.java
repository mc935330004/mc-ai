package org.example.ai.agent.graph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.graph.model.GraphSpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * GraphSpec JSON解析器。
 *
 * 这里只负责JSON结构解析；
 * 节点、边、拓扑和能力存在性由编译器校验。
 */
@Component
public class GraphSpecParser {

    private final ObjectReader graphReader;

    public GraphSpecParser(ObjectMapper objectMapper) {
        this.graphReader = objectMapper
                .readerFor(GraphSpec.class).with(
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public GraphSpec parse(String graphSpecJson) {
        if (!StringUtils.hasText(graphSpecJson)) {
            throw new BusinessException(
                    400,
                    "GraphSpec不能为空"
            );
        }

        try {
            GraphSpec graph =graphReader.readValue(graphSpecJson);

            if (graph == null) {
                throw new BusinessException(
                        400,
                        "GraphSpec必须是JSON对象"
                );
            }

            return graph;
        } catch (BusinessException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    400,
                    "GraphSpec解析失败：" +
                            safeMessage(exception)
            );
        }
    }

    private String safeMessage(JsonProcessingException exception) {

        String message =
                exception.getOriginalMessage();

        if (!StringUtils.hasText(message)) {
            return "JSON格式不正确";
        }

        return message.length() <= 300
                ? message
                : message.substring(0, 300);
    }
}