package org.example.ai.agent.business.dataset.impl;

import org.example.ai.agent.business.dataset.DatasetExecutionProofVerifier;
import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;

/**
 * 测试代码专用的同进程签名器，生产代码不暴露签名入口。
 */
public final class DatasetExecutionProofTestFixture {

    private final DatasetExecutionProofService service =
            new DatasetExecutionProofService();

    public DatasetExecutionProofVerifier verifier() {
        return service;
    }

    public DatasetExecutionResult sign(DatasetExecutionResult unsigned) {
        return service.sign(unsigned);
    }
}
