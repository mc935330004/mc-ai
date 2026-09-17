package org.example.ai.agent.business.intent;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 业务查询意图的确定性边界校验器。
 */
@Component
public class BusinessIntentValidator {

    private static final Pattern DATASET_CODE_PATTERN =
            Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private static final Set<String> EXPORT_FORMATS =
            Set.of("XLSX", "DOCX", "PDF");

    private static final int MAX_PERSON_DATASET_CODES = 4;
    private static final int MAX_SINGLE_METRIC_DATASET_CODES = 1;

    /**
     * 校验模型或调用方提供的业务查询语义。
     */
    public BusinessQueryIntent validate(BusinessQueryIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("业务查询意图不能为空");
        }

        validateDatasetCodes(intent);
        validateSingleMetric(intent);
        validateExportFormat(intent);
        validatePeriod(intent);

        return intent;
    }

    private void validateDatasetCodes(BusinessQueryIntent intent) {
        boolean personScope = intent.subjectType() == BusinessSubjectType.PERSON
                        || intent.subjectType() == BusinessSubjectType.DEPARTMENT;

        if (personScope && intent.datasetCodes().size() > MAX_PERSON_DATASET_CODES) {
            throw new BusinessException(400, "一次最多选择 4 个数据集");
        }

        Set<String> uniqueCodes = new HashSet<>();
        for (String datasetCode : intent.datasetCodes()) {
            if (datasetCode == null
                    || !DATASET_CODE_PATTERN.matcher(datasetCode).matches()) {
                throw new BusinessException(400, "数据集编码格式不合法");
            }

            if (!uniqueCodes.add(datasetCode)) {
                throw new BusinessException(400, "数据集编码不能重复");
            }
        }
    }

    /**
     * 第一阶段的单指标查询只支持项目主体和一个数据集。
     */
    private void validateSingleMetric(BusinessQueryIntent intent) {
        if (!intent.singleMetricRequested()) {
            return;
        }
        if (intent.subjectType() != null
                && intent.subjectType() != BusinessSubjectType.PROJECT) {
            throw new BusinessException(400, "单指标查询当前只支持项目主体");
        }

        if (intent.datasetCodes().size()
                > MAX_SINGLE_METRIC_DATASET_CODES) {
            throw new BusinessException(400, "单指标查询最多指定一个数据集");
        }

        if (StringUtils.hasText(intent.exportFormat())) {
            throw new BusinessException(400, "单指标查询暂不支持直接导出");
        }
    }

    private void validateExportFormat(BusinessQueryIntent intent) {
        if (StringUtils.hasText(intent.exportFormat())
                && !EXPORT_FORMATS.contains(intent.exportFormat())) {
            throw new BusinessException(
                    400,
                    "导出格式仅支持 XLSX、DOCX 或 PDF"
            );
        }
    }

    private void validatePeriod(BusinessQueryIntent intent) {
        if ((intent.periodStart() == null)
                != (intent.periodEnd() == null)) {
            throw new BusinessException(
                    400,
                    "业务数据时间范围必须同时提供开始和结束日期"
            );
        }

        if (intent.periodStart() != null
                && intent.periodEnd().isBefore(intent.periodStart())) {
            throw new BusinessException(400, "结束日期不能早于开始日期");
        }
    }
}