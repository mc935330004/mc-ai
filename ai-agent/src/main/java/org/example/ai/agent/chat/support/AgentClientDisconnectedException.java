package org.example.ai.agent.chat.support;

/**
 * SSE 客户端连接已断开异常。
 *
 * 客户端关闭页面、刷新、取消请求或网络中断时，
 * SseEmitter 写入会失败。该异常用于标识"连接已断开"，
 * 让调用方静默收尾，避免当作业务异常记录 ERROR 或二次发送错误事件。
 */
public class AgentClientDisconnectedException extends RuntimeException {

    public AgentClientDisconnectedException(String message) {
        super(message);
    }

    public AgentClientDisconnectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
