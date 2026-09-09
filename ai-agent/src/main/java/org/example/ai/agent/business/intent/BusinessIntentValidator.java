package org.example.ai.agent.business.intent;

import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 业务查询意图的确定性边界校验器，拒绝不安全编码和相互矛盾的显式条件。
 */
@Component
public class BusinessIntentValidator {

    private static final Pattern DATASET_CODE_PATTERN =
            Pattern.compile("^[A-Z][A-Z0-9_]{1,63}$");

    private static final Set<String> EXPORT_FORMATS =
            Set.of("XLSX", "DOCX", "PDF");

    private static final int MAX_DATASET_CODES = 4;

    /**
     * 校验模型或调用方提供的业务查询语义。
     */
    public BusinessQueryIntent validate(BusinessQueryIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("业务查询意图不能为空");
        }

        if (intent.datasetCodes().size() > MAX_DATASET_CODES) {
            throw new BusinessException(400, "一次最多选择 4 个数据集");
        }

        /*
         * 这里只校验编码形状，不维护具体数据集白名单；
         * 真实数据集解析必须由后续确定性代码完成。
         */
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

        if (StringUtils.hasText(intent.exportFormat())
                && !EXPORT_FORMATS.contains(intent.exportFormat())) {
            throw new BusinessException(400, "导出格式仅支持 XLSX、DOCX 或 PDF");
        }

        if (intent.periodStart() != null
                && intent.periodEnd() != null
                && intent.periodEnd().isBefore(intent.periodStart())) {
            throw new BusinessException(400, "结束日期不能早于开始日期");
        }

        /*
         * 项目年度与业务数据时间范围语义独立，
         * 校验器不得在二者之间推导或回填任何值。
         */
        return intent;
    }
}
