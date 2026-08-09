package org.example.ai.agent.graph.model;

/**
 * 报告展示字段与字段字典、工作流结果的绑定关系。
 *
 * @param key        前端稳定字段名称
 * @param fieldId    ai_field_dictionary 主键
 * @param sourcePath 当前区块数据中的取值路径
 */
public record ReportFieldBindingSpec(String key,Long fieldId,String sourcePath) {

}