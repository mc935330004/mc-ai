package org.example.ai.agent.chat.protocol.block;

/**
 * 经过后端安全处理的文件值。
 *
 * 只保存前端展示和下载需要的信息，
 * 不透传业务接口返回的完整文件对象。
 */
public record FileValue(
        String name,
        String url) {

    public FileValue {
        name = normalize(name);
        url = normalize(url);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}