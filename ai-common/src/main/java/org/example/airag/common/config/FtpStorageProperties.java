package org.example.airag.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.storage.ftp")
public class FtpStorageProperties {

    private String host;
    private int port = 21;
    private String username;
    private String password;
    private String baseDir;
    private int connectTimeoutMs = 10000;
    private int dataTimeoutMs = 30000;
    private String pathUrl;
}