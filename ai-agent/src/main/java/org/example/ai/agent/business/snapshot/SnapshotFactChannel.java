package org.example.ai.agent.business.snapshot;

/** 调用方当前用途需要读取的安全事实通道。 */
public enum SnapshotFactChannel {
    CALCULATION("calculation"),
    DISPLAY("display"),
    EXPORT("export"),
    MODEL("model");

    private final String jsonName;

    SnapshotFactChannel(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }
}
