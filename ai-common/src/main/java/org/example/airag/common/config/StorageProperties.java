package org.example.airag.common.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地文件存储配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.storage")
public class StorageProperties {

    /**
     * 本地知识库文件存储根目录。
     *
     * <p>数据库中只保存相对路径，真实文件会落到该目录下，后续替换为 S3/RustFS 时可以只替换存储层。</p>
     */
    private Path knowledgeBaseDir = Paths.get(System.getProperty("user.home"), ".ai-rag", "knowledgebase");
}
