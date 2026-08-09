package org.example.ai.agent.graph.model;

/**
 * 报告展示字段与字段字典、工作流结果的绑定关系。
 *
 * @param key            前端稳定字段名称
 * @param fieldId        普通字段字典 ID；文件字段时表示文件名字典 ID
 * @param sourcePath     普通字段取值路径；文件字段时表示文件数组路径
 * @param fileUrlFieldId 文件地址字段字典 ID
 * @param fileNamePath   文件数组单项中的文件名相对路径
 * @param fileUrlPath    文件数组单项中的文件地址相对路径
 */
public record ReportFieldBindingSpec(
        String key,
        Long fieldId,
        String sourcePath,
        Long fileUrlFieldId,
        String fileNamePath,
        String fileUrlPath) {

    /**
     * 是否配置了任意文件参数。
     */
    public boolean hasAnyFileConfig() {
        return fileUrlFieldId != null
                || hasText(fileNamePath)
                || hasText(fileUrlPath);
    }

    /**
     * 是否为完整文件列表绑定。
     */
    public boolean fileList() {
        return fileUrlFieldId != null
                && hasText(fileNamePath)
                && hasText(fileUrlPath);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}