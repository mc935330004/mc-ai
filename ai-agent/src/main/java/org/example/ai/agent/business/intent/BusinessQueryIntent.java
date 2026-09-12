package org.example.ai.agent.business.intent;

import org.example.ai.agent.business.model.BusinessSubjectType;

import java.time.LocalDate;
import java.util.List;

/**
 * 用户业务查询的结构化语义，仅描述查询对象、时间和数据诉求，不承载执行层决策；
 * anomalyPeopleRequested 只表示用户是否明确要求展示异常人员明细；
 * projectPeriodRequested 只表示用户是否明确要求按项目期间查询。
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
        boolean projectPeriodRequested
) {

    public BusinessQueryIntent {
        datasetCodes = datasetCodes == null
                ? List.of()
                : List.copyOf(datasetCodes);
    }
}
