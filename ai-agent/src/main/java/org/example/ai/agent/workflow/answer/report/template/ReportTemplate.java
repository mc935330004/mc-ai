package org.example.ai.agent.workflow.answer.report.template;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.ai.agent.chat.vo.ReportSchemaVO;
import org.example.ai.agent.common.enums.ReportType;

import java.util.List;

/**
 * 固定业务报告模板。
 *
 * 模板只负责把安全字段投影转换成固定区块，
 * 不查询数据库，不调用业务接口，也不调用大模型。
 */
public interface ReportTemplate {

    /**
     * 判断模板是否支持当前工作流。
     */
    boolean supports(String workflowCode);

    /**
     * 返回稳定的报告类型。
     */
    ReportType reportType();

    /**
     * 根据安全结果生成固定报告区块。
     */
    List<ReportSchemaVO.Section> buildSections(JsonNode safeResult);
}