package org.example.ai.agent.chat.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SSE 内容完整性 Hash 工具。
 */
public final class ContentHashUtils {

    private ContentHashUtils() {
    }

    /**
     * 计算 UTF-8 文本的 SHA-256。
     */
    public static String sha256(String content) {
        try {
            MessageDigest digest =MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(safeText(content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            /*
             * Java标准运行环境必须支持SHA-256。
             * 如果不存在，说明运行环境不完整。
             */
            throw new IllegalStateException(
                    "当前Java环境不支持SHA-256",
                    exception
            );
        }
    }

    /**
     * null文本安全处理。
     */
    private static String safeText(String value) {
        return value == null ? "" : value;
    }
}