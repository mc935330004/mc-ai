package org.example.ai.agent.workflow.answer.chunk;

/**
 * 发送给大模型的单个安全数据块。
 *
 * @param index         分块序号，从1开始
 * @param sourcePointer 数据在原始JSON中的位置
 * @param startIndex    数组开始下标，非数组分块时为空
 * @param endIndex      数组结束下标，非数组分块时为空
 * @param payloadJson   完整、合法的JSON分块
 * @param sha256        数据摘要，用于覆盖率和完整性核对
 * @param charCount     JSON字符数量
 */
public record WorkflowAnswerChunk(
        int index,
        String sourcePointer,
        Integer startIndex,
        Integer endIndex,
        String payloadJson,
        String sha256,
        int charCount) {
}