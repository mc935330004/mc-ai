package org.example.ai.agent.retention;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 运行数据保留与分批清理配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.data-retention")
public class RuntimeDataRetentionProperties {

    /**
     * 是否启用定时清理。
     */
    private boolean enabled = false;

    /**
     * 每条DELETE语句最多删除的记录数。
     */
    private int batchSize = 500;

    /**
     * 单次调度每类数据最多执行的批次数。
     */
    private int maxBatchesPerRun = 20;

    /**
     * 普通运行数据保留天数。
     */
    private int runtimeDays = 90;

    /**
     * 模型调用记录保留天数。
     */
    private int modelUsageDays = 180;

    /**
     * 知识问答日志保留天数。
     */
    private int knowledgeQueryDays = 180;

    /**
     * 审计记录保留天数。
     */
    private int auditDays = 365;

    /**
     * 启动时校验数据保留配置。
     */
    @PostConstruct
    public void validate() {
        if (!enabled) {
            return;
        }
        if (batchSize <= 0 || maxBatchesPerRun <= 0) {
            throw new IllegalStateException(
                    "数据清理批次配置必须大于0"
            );
        }
        if (runtimeDays <= 0
                || modelUsageDays <= 0
                || knowledgeQueryDays <= 0
                || auditDays <= 0) {
            throw new IllegalStateException(
                    "数据保留天数必须大于0"
            );
        }
        if (auditDays < runtimeDays) {
            throw new IllegalStateException(
                    "审计数据保留时间不能短于普通运行数据"
            );
        }
    }
}
