package org.example.ai.agent.common.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充处理器。
 *
 * 负责自动填充：
 * 1. createdAt：新增时填充
 * 2. updatedAt：新增和更新时填充
 */
@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    /**
     * 新增数据时自动填充创建时间和更新时间。
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();

        // 中文注释：只有字段为空时才自动填充，避免覆盖业务层已有时间。
        this.strictInsertFill(
                metaObject,
                "createdAt",
                LocalDateTime.class,
                now
        );

        this.strictInsertFill(
                metaObject,
                "updatedAt",
                LocalDateTime.class,
                now
        );
    }

    /**
     * 更新数据时自动填充更新时间。
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        this.strictUpdateFill(
                metaObject,
                "updatedAt",
                LocalDateTime.class,
                LocalDateTime.now()
        );
    }
}