package org.example.ai.agent.capability.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 动态表单文件上传结果。
 *
 * value：
 * 上传结果的唯一值，例如文件ID。
 *
 * label：
 * 页面展示的文件名称。
 *
 * item：
 * 上传接口返回的文件对象。
 * 只有FILE_UPLOAD配置了resultObjectPath时才返回；
 * 原有只使用value和label的上传配置不受影响。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityFileUploadVO {

    /**
     * 文件真实值，例如文件ID。
     */
    private Object value;

    /**
     * 页面展示的文件名称。
     */
    private String label;

    /**
     * 上传接口返回的完整文件对象。
     *
     * 用于同时生成：
     * 1. fileIds逗号字符串；
     * 2. fileList文件对象集合。
     */
    private Object item;
}