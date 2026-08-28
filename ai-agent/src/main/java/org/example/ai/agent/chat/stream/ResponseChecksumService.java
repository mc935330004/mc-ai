package org.example.ai.agent.chat.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.chat.protocol.response.ResponseDocument;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 对实际传输或保存的回答JSON计算校验值。
 */
@Service
public class ResponseChecksumService {

    private final ObjectMapper objectMapper;

    public ResponseChecksumService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 快照只序列化一次，传输正文与计算校验使用同一份文本。
     */
    public String serialize(ResponseDocument document) {
        Objects.requireNonNull(document, "回答文档不能为空");
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("回答文档序列化失败", exception);
        }
    }

    /**
     * 按原始UTF-8文本计算SHA-256，不重新解析或格式化JSON。
     */
    public String calculate(String documentJson) {
        Objects.requireNonNull(documentJson, "回答JSON不能为空");

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = documentJson.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "当前运行环境不支持SHA-256",
                    exception
            );
        }
    }
}