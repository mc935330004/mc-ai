package org.example.ai.agent.alert.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.alert.entity.AlertRecord;
import org.example.ai.agent.alert.vo.AlertSummaryVO;

/**
 * 告警记录数据访问接口。
 */
@Mapper
public interface AlertRecordMapper extends BaseMapper<AlertRecord> {

    /**
     * 新增告警或原子累加已有活动告警。
     *
     * 该操作由一条MySQL语句完成，
     * 避免多实例并发产生重复告警。
     *
     * @param record 本次告警数据
     * @return 受影响行数
     */
    int upsertOccurrence( @Param("record") AlertRecord record );
    /**
     * 查询告警数量汇总。
     */
    AlertSummaryVO selectSummary();

    /**
     * 分页查询告警。
     */
    Page<AlertRecord> pageAlerts(Page<AlertRecord> page,
                                 @Param("status") String status,
                                 @Param("severity") String severity,
                                 @Param("workflowCode") String workflowCode,
                                 @Param("errorCode") String errorCode);

}