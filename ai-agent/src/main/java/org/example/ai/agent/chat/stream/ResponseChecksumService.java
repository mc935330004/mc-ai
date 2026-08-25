package org.example.ai.agent.chat.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.chat.protocol.response.ResponseDocument;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 回答快照SHA-256校验服务。
 *
 * 校验值用于判断前端恢复后的完整回答，
 * 是否与后端最终快照一致。
 */
@Service
public class ResponseChecksumService {

    private final ObjectMapper objectMapper;

    public ResponseChecksumService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 计算完整回答的SHA-256校验值。
     */
    public String calculate(ResponseDocument document) {
        Objects.requireNonNull(
                document,
                "计算校验值时document不能为空"
        );

        try {
            byte[] documentBytes = objectMapper.writeValueAsBytes(document);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] checksumBytes = digest.digest(documentBytes);

            return HexFormat.of().formatHex(checksumBytes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "完整回答序列化失败，无法计算校验值",
                    e
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "当前运行环境不支持SHA-256",
                    e
            );
        }
    }
}