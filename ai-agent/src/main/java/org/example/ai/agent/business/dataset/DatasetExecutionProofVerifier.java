package org.example.ai.agent.business.dataset;

import org.example.ai.agent.business.dataset.model.DatasetExecutionResult;

/**
 * 验证数据集执行结果是否来自当前进程内的受控执行链。
 *
 * 该证明不替代用户权限、当前配置或业务数据范围校验。
 */
public interface DatasetExecutionProofVerifier {

    boolean verify(DatasetExecutionResult result);
}
