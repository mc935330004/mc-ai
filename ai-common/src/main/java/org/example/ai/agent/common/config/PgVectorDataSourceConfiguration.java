package org.example.ai.agent.common.config;

import javax.sql.DataSource;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.observation.VectorStoreObservationConvention;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * PgVector 专用数据源和向量存储配置。
 *
 * <p>这里将向量库连接配置与应用主数据源分开，便于单独管理 pgvector 表、schema 和连接参数。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PgVectorStoreProperties.class)
public class PgVectorDataSourceConfiguration {

    @Bean
    @ConfigurationProperties("app.pgvector.datasource")
    public DataSourceProperties pgVectorDataSourceProperties() {
        // 绑定 app.pgvector.datasource 下的连接地址、账号、密码等配置。
        return new DataSourceProperties();
    }

    @Bean
    public DataSource pgVectorDataSource(
            @Qualifier("pgVectorDataSourceProperties") DataSourceProperties properties) {
        // 根据 PgVector 专用连接属性创建独立数据源。
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean
    public JdbcTemplate pgVectorJdbcTemplate(
            @Qualifier("pgVectorDataSource") DataSource dataSource) {
        // PgVectorStore 通过 JdbcTemplate 访问 pgvector 表。
        return new JdbcTemplate(dataSource);
    }

    /**
     * 创建 Spring AI 的 PgVectorStore。
     *
     * <p>当没有自定义 PgVectorStore，且向量库类型未配置或配置为 pgvector 时启用。</p>
     */
    @Bean
    @ConditionalOnMissingBean(PgVectorStore.class)
    @ConditionalOnProperty(name = "spring.ai.vectorstore.type", havingValue = "pgvector", matchIfMissing = true)
    public PgVectorStore pgVectorStore(
            @Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            PgVectorStoreProperties properties,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<VectorStoreObservationConvention> customObservationConvention,
            ObjectProvider<BatchingStrategy> batchingStrategy) {
        // 将 Spring AI 自动配置属性映射到 PgVectorStore builder，保持和 application.yml 配置一致。
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                // 数据表与 schema 相关配置。
                .schemaName(properties.getSchemaName())
                .idType(properties.getIdType())
                .vectorTableName(properties.getTableName())
                .vectorTableValidationsEnabled(properties.isSchemaValidation())
                // 向量维度、距离算法和索引配置。
                .dimensions(properties.getDimensions())
                .distanceType(properties.getDistanceType())
                .removeExistingVectorStoreTable(properties.isRemoveExistingVectorStoreTable())
                .indexType(properties.getIndexType())
                .initializeSchema(properties.isInitializeSchema())
                // 观测与批量写入配置。
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .customObservationConvention(customObservationConvention.getIfAvailable())
                .batchingStrategy(batchingStrategy.getIfAvailable(TokenCountBatchingStrategy::new))
                .maxDocumentBatchSize(properties.getMaxDocumentBatchSize())
                .build();
    }
}
