package org.example.ai.agent.business.report.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.example.ai.agent.business.report.entity.CompositeReportTask;

import java.util.List;

/** 组合报告任务数据访问；全部SQL统一维护在Mapper XML。 */
@Mapper
public interface CompositeReportTaskMapper {

    CompositeReportTask selectByRequestKey(@Param("requestKey") String requestKey);

    CompositeReportTask selectByTaskId(@Param("taskId") String taskId);

    int insertTask(@Param("task") CompositeReportTask task);

    int recoverExpiredLeases();

    List<String> selectClaimCandidateIds(@Param("limit") int limit);

    int tryClaim(
            @Param("taskId") String taskId,
            @Param("workerId") String workerId,
            @Param("leaseSeconds") int leaseSeconds);

    int renewLease(
            @Param("taskId") String taskId,
            @Param("workerId") String workerId,
            @Param("leaseSeconds") int leaseSeconds);

    int completeWithArtifact(
            @Param("taskId") String taskId,
            @Param("workerId") String workerId,
            @Param("dataComplete") boolean dataComplete,
            @Param("storagePath") String storagePath,
            @Param("fileName") String fileName,
            @Param("mimeType") String mimeType,
            @Param("fileSize") long fileSize,
            @Param("checksum") String checksum);

    int markRetry(
            @Param("taskId") String taskId,
            @Param("workerId") String workerId,
            @Param("safeErrorCode") String safeErrorCode,
            @Param("safeErrorMessage") String safeErrorMessage,
            @Param("retryDelaySeconds") int retryDelaySeconds);

    int markFailed(
            @Param("taskId") String taskId,
            @Param("workerId") String workerId,
            @Param("safeErrorCode") String safeErrorCode,
            @Param("safeErrorMessage") String safeErrorMessage);

    int cancelIfCancellable(@Param("taskId") String taskId, @Param("userId") String userId);

    List<CompositeReportTask> selectExpiredArtifactCandidates(@Param("limit") int limit);

    int markExpiredAndClearArtifact(
            @Param("taskId") String taskId,
            @Param("storagePath") String storagePath);
}
