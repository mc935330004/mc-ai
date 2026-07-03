package org.example.airag.modules.KnowledgeLog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName("knowledge_query_log")
public class KnowledgeQueryLog {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户问题
     */
    @TableField("question")
    private String question;

    /**
     * 模型回答
     */
    @TableField("answer")
    private String answer;

    /**
     * 召回数量
     */
    @TableField("top_k")
    private Integer topK;

    /**
     * 最低相似度
     */
    @TableField("min_score")
    private BigDecimal minScore;

    /**
     * 状态：SUCCESS 成功、NO_RESULT 无结果、FAILED 失败
     */
    @TableField("status")
    private String status;

    /**
     * 失败原因
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 耗时毫秒
     */
    @TableField("duration_ms")
    private Long durationMs;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 引用数量
     */
    @TableField(exist = false)
    private Integer referenceCount;
}