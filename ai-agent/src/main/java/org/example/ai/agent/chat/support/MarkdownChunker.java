package org.example.ai.agent.chat.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown 安全分块器。
 *
 * 作用：
 * 将一个较大的最终 Markdown 拆成多个 SSE 增量事件。
 */
@Component
public class MarkdownChunker {

    /**
     * 按建议大小拆分 Markdown。
     *
     * 优先在换行符处分块，
     * 同时避免截断 Unicode 代理字符。
     */
    public List<String> split(String markdown,int preferredSize) {
        if (!StringUtils.hasText(markdown)) {
            return List.of();
        }
        int safeSize = Math.max(preferredSize, 50);

        List<String> chunks = new ArrayList<>();

        int start = 0;
        while (start < markdown.length()) {
            int expectedEnd = Math.min( start + safeSize,markdown.length());
            int end = expectedEnd;
            /*
             * 在建议范围内优先寻找最后一个换行符，
             * 减少表格行和段落被拆开。
             */
            if (expectedEnd < markdown.length()) {
                int lastNewline =markdown.lastIndexOf('\n', expectedEnd);
                if (lastNewline > start + safeSize / 2) {
                    end = lastNewline + 1;
                }
            }

            /*
             * Java char 使用 UTF-16。
             * 如果分块边界正好位于代理字符中间，
             * 将结束位置向前移动。
             */
            if (end < markdown.length()&& end > start && Character.isHighSurrogate(
                            markdown.charAt(end - 1))) {
                end--;
            }
            /*
             * 防御性处理，确保循环一定向前推进。
             */
            if (end <= start) {
                end = expectedEnd;
            }
            chunks.add(markdown.substring(start, end));
            start = end;
        }
        return chunks;
    }
}