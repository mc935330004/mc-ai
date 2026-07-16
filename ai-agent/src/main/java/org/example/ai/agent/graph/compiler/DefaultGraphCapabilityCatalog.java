package org.example.ai.agent.graph.compiler;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.service.CapabilityDefinitionService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 默认能力目录实现。
 */
@Component
@RequiredArgsConstructor
public class DefaultGraphCapabilityCatalog
        implements GraphCapabilityCatalog {

    private final CapabilityDefinitionService
            capabilityDefinitionService;

    @Override
    public boolean isCallable(
            String capabilityCode) {

        if (!StringUtils.hasText(capabilityCode)) {
            return false;
        }

        CapabilityDefinition capability =
                capabilityDefinitionService
                        .getEnabledByCode(
                                capabilityCode
                        );

        /*
         * 当前阶段的Workflow只允许查询。
         *
         * WRITE能力以后必须增加人工确认节点、
         * 挂起恢复和幂等控制，不能直接混入查询工作流。
         */
        return capability != null
                && "READ".equalsIgnoreCase(
                capability.getSideEffect()
        );
    }
}