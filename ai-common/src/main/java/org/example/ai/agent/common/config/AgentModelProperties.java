package org.example.ai.agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app.agent.model")
public class AgentModelProperties {

    /** 中文注释：默认模型编码，前端不传时使用。 */
    private String defaultCode;

    /** 中文注释：模型列表从 application.yml 读取，前端下拉框也读取这里。 */
    private List<ModelItem> models = new ArrayList<>();

    /**
     * 中文注释：解析前端选择的聊天模型。
     *
     * 前端未传时使用默认模型；
     * 前端传入不存在或已停用的模型时直接拒绝，避免静默切换。
     */
    public ModelItem resolve(String modelCode) {
        if (!StringUtils.hasText(modelCode)) {
            return defaultModel();
        }

        return models.stream()
                .filter(ModelItem::isEnabled)
                .filter(item -> modelCode.equals(item.getCode()))
                .findFirst()
                .orElseThrow(() ->
                        new IllegalArgumentException("模型不存在或已停用：" + modelCode));
    }

    public ModelItem defaultModel() {
        return models.stream()
                .filter(ModelItem::isEnabled)
                .filter(item -> item.getCode().equals(defaultCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("未配置可用默认模型"));
    }

    @Data
    public static class ModelItem {
        private String code;
        private String name;
        private String provider;
        private String modelName;
        private boolean enabled = true;
    }
}