package org.example.ai.agent.capability.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 动态表单文件上传结果。
 *
 * value：最终写入WRITE表单的真实文件值，例如附件ID或文件路径。
 * label：页面展示的文件名称。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityFileUploadVO {

    private Object value;

    private String label;
}