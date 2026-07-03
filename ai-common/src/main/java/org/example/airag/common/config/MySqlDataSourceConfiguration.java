package org.example.airag.common.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 默认业务数据源配置。
 *
 * <p>项目同时存在 MySQL 和 PGVector 两个 JDBC 数据源时，需要显式声明 MySQL 为主数据源，
 * 避免自定义的 PGVector 数据源让 Spring Boot 跳过默认 DataSource 自动配置。</p>
 */
@Configuration(proxyBeanMethods = false)
public class MySqlDataSourceConfiguration {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties mysqlDataSourceProperties() {
        // 绑定 spring.datasource 下的 MySQL 连接地址、账号和密码。
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(
            @Qualifier("mysqlDataSourceProperties") DataSourceProperties properties) {
        // 暴露标准 dataSource Bean，供 MyBatis、事务管理和默认 JDBC 组件使用。
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }
}
