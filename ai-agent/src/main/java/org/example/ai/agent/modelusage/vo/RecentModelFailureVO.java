package org.example.ai.agent.modelusage.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端最近模型失败记录。
 *
 * 不返回供应商原始响应和内部异常堆栈。
 */
@Data
public class RecentModelFailureVO {

    private String runId;

    private String modelCode;

    private String provider;

    private String modelName;

    private String callType;

    private String errorCategory;

    /**
     * 根据失败分类生成的安全提示。
     */
    private String errorMessage;

    private Long durationMs;

    @JsonFormat(
            pattern = "yyyy-MM-dd HH:mm:ss",
            timezone = "GMT+8"
    )
    private LocalDateTime createdAt;
}