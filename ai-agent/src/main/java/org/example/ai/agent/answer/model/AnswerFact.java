package org.example.ai.agent.answer.model;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.common.enums.FactSourceType;

import java.util.List;

/**
 * 从业务数据和字段字典中提取出的标准事实。
 *
 * 事实只保存真实值、格式化结果、来源和缺失原因，
 * 不负责选择Block，也不负责生成Markdown。
 */
@Data
@Builder
public class AnswerFact {

    /**
     * 事实唯一标识。
     *
     * 格式：
     * capabilityCode:fieldPath:recordPath
     */
    private String factKey;

    /**
     * 业务能力编码。
     */
    private String capabilityCode;

    /**
     * 字段业务语义编码。
     *
     * 当前字段字典尚未增加fieldCode时，
     * 暂时使用fieldName。
     */
    private String fieldCode;

    /**
     * 字段重要程度。
     */
    private String importance;

    /**
     * 字段建议展示组件。
     */
    private String displayComponent;

    /**
     * 是否优先进入汇总。
     */
    private boolean summary;

    /**
     * 字段单位。
     */
    private String unit;

    /**
     * 数字展示精度。
     */
    private Integer precisionScale;

    /**
     * 字段机器名称。
     */
    private String fieldName;

    /**
     * 字段字典中的完整取值路径。
     */
    private String fieldPath;

    /**
     * 字段中文名称。
     */
    private String label;

    /**
     * 业务接口返回的原始值。
     */
    private Object rawValue;

    /**
     * 后端确定性格式化后的值。
     */
    private String formattedValue;

    /**
     * 字段数据类型。
     */
    private String valueType;

    /**
     * 字段展示格式。
     */
    private String displayFormat;

    /**
     * 字段业务含义。
     */
    private String meaning;

    /**
     * 字段展示分组。
     */
    private String displayGroup;

    /**
     * 字段展示顺序。
     */
    private Integer displayOrder;

    /**
     * 是否为必答字段。
     */
    private boolean requiredOutput;

    /**
     * 是否允许发送给大模型。
     */
    private boolean modelVisible;

    /**
     * 是否允许展示给用户。
     */
    private boolean userVisible;

    /**
     * 字段是否缺失。
     */
    private boolean missing;

    /**
     * 字段缺失原因。
     *
     * 例如：
     * PATH_INVALID
     * PATH_NOT_FOUND
     * VALUE_NULL
     * ARRAY_EMPTY
     */
    private String missingReason;

    /**
     * 当前字段所属记录路径。
     *
     * 例如：
     * $.data.records[0]
     */
    private String recordPath;

    /**
     * 当前字段所属集合。
     *
     * 例如：
     * capabilityCode:$.data.records[]
     */
    private String collectionKey;
    /**
     * 计算公式表达式。
     *
     * 只用于后端审计和问题排查，
     * 不交给前端执行。
     */
    private String calculationExpression;

    /**
     * 计算公式引用的字段编码。
     */
    @Builder.Default
    private List<String> sourceFieldCodes = List.of();

    /**
     * 计算状态。
     *
     * SUCCESS：计算成功
     * FAILED：计算失败
     */
    private String calculationStatus;
    /**
     * 事实来源。
     */
    @Builder.Default
    private FactSourceType sourceType = FactSourceType.RAW;
}