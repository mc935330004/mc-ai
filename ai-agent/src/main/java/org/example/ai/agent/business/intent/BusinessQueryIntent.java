package org.example.ai.agent.business.intent;

import org.example.ai.agent.business.model.BusinessSubjectType;

import java.time.LocalDate;
import java.util.List;

/**
 * 用户业务查询的结构化语义。
 *
 * 只描述用户表达，不承载执行层、权限或数据库查询决策。
 */
public record BusinessQueryIntent(
        BusinessSubjectType subjectType,
        String projectCode,
        String personName,
        String employeeNo,
        Integer projectYear,
        LocalDate periodStart,
        LocalDate periodEnd,
        List<String> datasetCodes,
        boolean refresh,
        String exportFormat,
        boolean anomalyPeopleRequested,
        boolean projectPeriodRequested,
        boolean singleMetricRequested) {

    public BusinessQueryIntent {
        datasetCodes = datasetCodes == null ? List.of() : List.copyOf(datasetCodes);
    }

    /**
     * 普通业务查询默认不是单指标查询。
     *
     * 该构造器用于减少现有人员、部门查询代码的无关改动，
     * 不包含旧版本兼容或双轨逻辑。
     */
    public BusinessQueryIntent(BusinessSubjectType subjectType, String projectCode, String personName,
                               String employeeNo, Integer projectYear, LocalDate periodStart, LocalDate periodEnd,
                               List<String> datasetCodes, boolean refresh, String exportFormat, boolean anomalyPeopleRequested, boolean projectPeriodRequested) {
        this(subjectType, projectCode, personName, employeeNo, projectYear,
                periodStart, periodEnd, datasetCodes, refresh, exportFormat, anomalyPeopleRequested, projectPeriodRequested, false
        );
    }
}