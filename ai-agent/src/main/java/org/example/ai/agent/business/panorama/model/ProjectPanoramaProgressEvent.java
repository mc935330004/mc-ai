package org.example.ai.agent.business.panorama.model;

import org.example.ai.agent.business.model.DatasetExecutionStatus;

/**
 * 可映射为前端 DATASET_STATUS 事件的模块进度。
 */
public record ProjectPanoramaProgressEvent(
        String datasetCode,
        DatasetExecutionStatus status,
        int index,
        int total) {
}
