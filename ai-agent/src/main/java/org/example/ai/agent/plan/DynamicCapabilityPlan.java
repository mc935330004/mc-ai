package org.example.ai.agent.plan;

import lombok.Data;
import org.example.ai.agent.capability.routing.CapabilityAlternative;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态能力规划结果。
 *
 * 根据用户问题，从 ai_capability_definition 中选择一个能力，
 * 并生成该能力需要的入参。
 */
@Data
public class DynamicCapabilityPlan {

    /**
     * 是否找到与用户问题匹配的业务能力。
     *
     * true：可以继续调用业务系统。
     * false：当前能力目录中没有合适的能力，需要追问用户。
     */
    private boolean matched;

    /**
     * 选中的能力编码。
     *
     * matched=false 时必须为空。
     */
    private String capabilityCode;

    /**
     * 调用业务能力需要的参数。
     */
    private Map<String, Object> input = new LinkedHashMap<>();

    /**
     * 选择或不选择该能力的原因，主要用于调试和运行追踪。
     */
    private String reason;

    /**
     * 没有匹配能力时，向用户提出的补充问题。
     */
    private String clarifyQuestion;

    /**
     * 能力名称，由后端根据数据库配置写入。
     */
    private String capabilityName;

    /**
     * 能力副作用级别，由数据库配置决定，不能信任模型输出。
     */
    private String sideEffect;

    /**
     * 是否需要用户确认，由数据库配置决定。
     */
    private boolean requireConfirm;

    /**
     * 大模型对最终能力选择结果的置信度。
     *
     * 范围建议为 0～1。
     * 该值不能直接决定是否执行，还需要经过 CapabilitySelectionGuard。
     */
    private double confidence;

    /**
     * 其他可能的候选能力。
     *
     * 当候选数量大于 1 时，要求模型至少返回第二名，
     * 以便后端判断第一名与第二名是否过于接近。
     */
    private List<CapabilityAlternative> alternatives =new ArrayList<>();

    /**
     * 操作预览展示参数。
     *
     * input保存业务接口需要的真实ID；
     * displayInput保存用户看到的中文名称。
     */
    private Map<String, Object> displayInput =new LinkedHashMap<>();
}