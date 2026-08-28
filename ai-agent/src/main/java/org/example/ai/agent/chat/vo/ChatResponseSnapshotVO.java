package org.example.ai.agent.chat.vo;

/**
 * 回答恢复查询结果。
 *
 * READY：已保存最终快照，不代表业务一定成功。
 * PENDING：任务仍在运行，可能只有基础快照。
 * UNAVAILABLE：没有最终快照，也没有当前进程中的活动任务。
 */
public record ChatResponseSnapshotVO(String state, String documentJson, String checksum) {

}