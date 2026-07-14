package org.example.ai.agent.capability.index;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.result.Result;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 能力向量索引管理接口。
 *
 * 生产环境应限制为管理员权限。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent/capabilities/vector-index")
public class CapabilityVectorIndexController {

    /**
     * 向量索引服务是可选组件。
     *
     * 没有配置 VectorStore 时，应用仍然允许启动，
     * 但重建接口会返回明确的未启用提示。
     */
    private final ObjectProvider<CapabilityVectorIndexService> indexServiceProvider;

    /**
     * 重建全部已发布、已启用能力的向量索引。
     */
    @PostMapping("/rebuild")
    public Result<CapabilityIndexRebuildResultVO> rebuild() {
        CapabilityVectorIndexService indexService = indexServiceProvider.getIfAvailable();

        if (indexService == null) {
            return Result.error("能力向量索引未启用，请检查 VectorStore、Embedding 模型和 PGVector 配置");
        }
        return Result.success( indexService.rebuildAll());
    }
}