package org.example.airag.common.file;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 文本内容 Hash 服务。
 *
 * 用于 knowledge_chunk.content_hash：
 * - 判断切片内容是否重复
 * - 后续支持增量向量化
 * - 方便排查同一版本中切片是否变化
 */
@Service
public class ContentHashService {

    /**
     * 计算文本 SHA-256。
     */
    public String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));

            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算内容Hash失败", e);
        }
    }
}