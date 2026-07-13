package org.example.ai.agent.trace.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.trace.entity.RunTrace;

/**
 * Agent 运行主记录 Mapper。
 *
 * 简单单表 CRUD 直接用 BaseMapper，不需要 XML。
 */
@Mapper
public interface RunTraceMapper extends BaseMapper<RunTrace> {
    /**
     * 原子累加一次模型调用的 Token。
     *
     * 使用数据库原子更新，避免同一个 runId 下多个模型调用并发时互相覆盖。
     *
     * @param runId Agent 运行 ID
     * @param promptTokens 输入 Token
     * @param completionTokens 输出 Token
     * @param totalTokens 总 Token
     * @return 更新行数
     */
    int incrementTokenUsage( @Param("runId") String runId,
                             @Param("promptTokens") int promptTokens,
                             @Param("completionTokens") int completionTokens,
                             @Param("totalTokens") int totalTokens);
}