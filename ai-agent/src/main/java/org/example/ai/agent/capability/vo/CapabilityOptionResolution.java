package org.example.ai.agent.capability.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WRITE动态表单选项解析结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityOptionResolution {

    /**
     * 是否已经完成全部选项解析。
     */
    private boolean ready;

    /**
     * 最终提交给业务WRITE接口的参数。
     *
     * 远程下拉字段保存真实ID。
     */
    @Builder.Default
    private Map<String, Object> requestInput =new LinkedHashMap<>();

    /**
     * 操作预览使用的展示参数。
     *
     * 远程下拉字段保存中文名称。
     */
    @Builder.Default
    private Map<String, Object> displayInput = new LinkedHashMap<>();

    /**
     * 无匹配、多匹配或缺少依赖字段时的追问。
     */
    private String clarifyQuestion;
}