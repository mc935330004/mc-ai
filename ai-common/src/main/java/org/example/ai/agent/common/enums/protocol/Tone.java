package org.example.ai.agent.common.enums.protocol;

/**
 * 状态、提示和风险的视觉语义。
 *
 * 后端只返回语义，不返回CSS颜色值，
 * 具体颜色由前端统一控制。
 */
public enum Tone {

    /**
     * 默认样式。
     */
    DEFAULT,

    /**
     * 普通提示。
     */
    INFO,

    /**
     * 成功状态。
     */
    SUCCESS,

    /**
     * 警告状态。
     */
    WARNING,

    /**
     * 错误或高风险状态。
     */
    DANGER,

    /**
     * 弱化状态。
     */
    MUTED
}