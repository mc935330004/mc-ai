package org.example.ai.agent.capability.index;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 能力索引同步监听器。
 *
 * 必须在 MySQL 事务提交后执行。
 * 不能在数据库事务提交前调用 Embedding 服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilityIndexEventListener {

    private final CapabilityDefinitionService capabilityDefinitionService;
    /**
     * 使用 ObjectProvider 延迟获取向量索引服务。
     *
     * 测试环境或未配置 VectorStore 时，
     * CapabilityVectorIndexService 不会被创建，
     * 但不能因此导致整个 Spring 容器启动失败。
     */
    private final ObjectProvider<CapabilityVectorIndexService> indexServiceProvider;

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true
    )
    public void onCapabilityChanged(
            CapabilityIndexChangedEvent event) {

        if (event == null
                || event.capabilityCode() == null) {
            return;
        }
        /*
         * VectorStore 未配置时，
         * CapabilityVectorIndexService 不存在。
         *
         * 此时只跳过向量索引同步，
         * 不影响 MySQL 中的能力配置和普通关键词路由。
         */
        CapabilityVectorIndexService indexService =indexServiceProvider.getIfAvailable();

        if (indexService == null) {
            log.debug( "未启用能力向量索引，跳过索引同步: capabilityCode={}", event.capabilityCode());
            return;
        }
        try {
            CapabilityDefinition capability =
                    capabilityDefinitionService
                            .getEnabledByCode(
                                    event.capabilityCode()
                            );

            /*
             * 查询不到说明能力已停用、禁用或取消发布。
             */
            if (capability == null) {
                indexService.delete(
                        event.capabilityCode()
                );
                return;
            }

            indexService.index(capability);
        } catch (Exception exception) {
            /*
             * 向量索引失败不能回滚已经提交的业务配置。
             * 记录错误后，可通过 rebuild 接口补偿。
             */
            log.error(
                    "能力向量索引同步失败: capabilityCode={}, error={}",
                    event.capabilityCode(),
                    exception.getMessage(),
                    exception
            );
        }
    }
}