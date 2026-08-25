package org.example.ai.agent.chat.stream;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单次回答的SSE事件序号生成器。
 *
 * 每次回答创建一个实例，
 * 不允许作为全局单例在不同回答之间共用。
 */
public final class ResponseSequenceGenerator {

    private final AtomicLong sequence;

    /**
     * 创建一个从0开始的序号生成器。
     *
     * 第一个事件序号为1。
     */
    public ResponseSequenceGenerator() {
        this(0);
    }

    /**
     * 使用已经持久化的最后序号恢复生成器。
     *
     * @param lastSequence 已经发送的最后事件序号
     */
    public ResponseSequenceGenerator(long lastSequence) {
        this.sequence = new AtomicLong(
                Math.max(lastSequence, 0)
        );
    }

    /**
     * 获取下一个事件序号。
     */
    public long next() {
        return sequence.incrementAndGet();
    }

    /**
     * 获取当前已经分配的最后序号。
     */
    public long current() {
        return sequence.get();
    }
}