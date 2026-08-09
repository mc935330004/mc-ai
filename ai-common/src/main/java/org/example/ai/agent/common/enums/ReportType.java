package org.example.ai.agent.common.enums;

/**
 * 统一报告类型。
 *
 * 当前阶段只提供稳定类型常量。
 * workflowCode 与报告类型的映射由下一阶段的模板注册中心负责。
 */
public enum ReportType {

    GENERIC_WORKFLOW_REPORT,

    PROJECT_SETTLEMENT,

    PROJECT_ESTIMATE,

    PROJECT_CASH_FLOW,

    PROJECT_OUTPUT,

    PROJECT_CONTRACT,

    PROJECT_RECEIPT,

    PROJECT_COST,

    PROJECT_PROGRESS,

    PROJECT_RISK
}