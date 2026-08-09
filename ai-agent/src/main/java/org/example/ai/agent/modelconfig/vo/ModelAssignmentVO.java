package org.example.ai.agent.modelconfig.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 模型授权配置返回对象。
 */
@Data
@Builder
public class ModelAssignmentVO {

    private String subjectType;

    private String subjectId;

    private List<ModelAssignmentItemVO> models;
}