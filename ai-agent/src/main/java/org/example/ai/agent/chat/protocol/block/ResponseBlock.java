package org.example.ai.agent.chat.protocol.block;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.example.ai.agent.common.enums.protocol.BlockSource;
import org.example.ai.agent.common.enums.protocol.BlockStatus;
import org.example.ai.agent.common.enums.protocol.BlockType;
import com.fasterxml.jackson.annotation.JsonProperty;
/**
 * CHAT和REPORT共用的回答区块。
 *
 * 不同业务只能组合区块，
 * 不能在区块协议中增加业务专属页面结构。
 */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = TextBlock.class, name = "TEXT"),
        @JsonSubTypes.Type(value = MetricsBlock.class, name = "METRICS"),
        @JsonSubTypes.Type(value = KeyValueBlock.class, name = "KEY_VALUE"),
        @JsonSubTypes.Type(value = TableBlock.class, name = "TABLE"),
        @JsonSubTypes.Type(value = TreeTableBlock.class, name = "TREE_TABLE"),
        @JsonSubTypes.Type(value = GroupTableBlock.class, name = "GROUP_TABLE"),
        @JsonSubTypes.Type(value = CalloutBlock.class, name = "CALLOUT"),
        @JsonSubTypes.Type(value = StatusBlock.class, name = "STATUS"),
        @JsonSubTypes.Type(value = StatusListBlock.class, name = "STATUS_LIST"),
        @JsonSubTypes.Type(value = WarningsBlock.class, name = "WARNINGS"),
        @JsonSubTypes.Type(value = ArtifactBlock.class, name = "ARTIFACT")
})
public sealed interface ResponseBlock permits
        TextBlock,
        MetricsBlock,
        KeyValueBlock,
        TableBlock,
        TreeTableBlock,
        GroupTableBlock,
        CalloutBlock,
        StatusBlock,
        StatusListBlock,
        WarningsBlock,
        ArtifactBlock {

    /**
     * 区块唯一标识。
     */
    String id();

    /**
     * 区块类型。
     */
    @JsonProperty("type")
    BlockType type();

    /**
     * 区块标题。
     */
    String title();

    /**
     * 展示顺序。
     */
    int order();

    /**
     * 区块状态。
     */
    BlockStatus status();

    /**
     * 区块数据来源。
     */
    BlockSource source();
}

/**
 * 区块通用参数校验。
 *
 * 只处理协议层的必要校验，
 * 不承载业务判断和页面布局逻辑。
 */
final class BlockSupport {

    private BlockSupport() {
    }

    static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(
                    "Block id不能为空"
            );
        }
        return id.trim();
    }

    static String normalizeTitle(String title) {
        return title == null ? "" : title.trim();
    }

    static int normalizeOrder(int order) {
        return Math.max(order, 0);
    }

    static BlockStatus normalizeStatus(BlockStatus status) {

        return status == null
                ? BlockStatus.READY
                : status;
    }

    static BlockSource normalizeSource(BlockSource source) {

        return source == null
                ? BlockSource.SYSTEM
                : source;
    }
}
