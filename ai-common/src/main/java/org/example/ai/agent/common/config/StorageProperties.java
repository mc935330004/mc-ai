package org.example.ai.agent.common.config;

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

    /**
     * 报告制品独立存储根目录，避免报告相对路径被解析到知识库目录。
     * 生产环境必须通过操作系统ACL确保该目录只有应用运行账号可写。
     */
    private Path reportDir = Paths.get(System.getProperty("user.home"), ".ai-rag", "report-artifacts");
}
