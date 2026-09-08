package org.example.ai.agent.business.report;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.example.ai.agent.business.report.entity.CompositeReportSection;
import org.example.ai.agent.business.report.entity.CompositeReportTask;
import org.example.ai.agent.business.report.mapper.CompositeReportSectionMapper;
import org.example.ai.agent.business.report.mapper.CompositeReportTaskMapper;
import org.example.ai.agent.business.snapshot.BusinessSnapshotAccessService;
import org.example.ai.agent.business.snapshot.BusinessSnapshotAccessService.AccessCommand;
import org.example.ai.agent.business.snapshot.BusinessSnapshotAccessService.AccessGrant;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.file.SafeArtifactStorageService;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 报告任务查询和下载安全边界，下载时对全部章节重新执行当前权限校验。
 */
@Service
public class ReportDownloadService {

    private static final String GENERIC_DENIAL = "报告任务不存在或无权访问";
    private static final Set<String> DOWNLOADABLE_STATUSES = Set.of("SUCCESS", "PARTIAL_SUCCESS");
    private static final Map<String, String> MIME_TYPES = Map.of(
            "PDF", "application/pdf",
            "DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "XLSX", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    );

    private final CompositeReportTaskMapper taskMapper;
    private final CompositeReportSectionMapper sectionMapper;
    private final BusinessSnapshotAccessService accessService;
    private final CurrentUserProvider currentUser;
    private final SafeArtifactStorageService storage;
    private final Clock clock;

    @Autowired
    public ReportDownloadService(
            CompositeReportTaskMapper taskMapper,
            CompositeReportSectionMapper sectionMapper,
            BusinessSnapshotAccessService accessService,
            CurrentUserProvider currentUser,
            SafeArtifactStorageService storage) {
        this(taskMapper, sectionMapper, accessService, currentUser, storage, Clock.systemDefaultZone());
    }

    /** 测试构造器允许固定过期判断时间。 */
    public ReportDownloadService(
            CompositeReportTaskMapper taskMapper,
            CompositeReportSectionMapper sectionMapper,
            BusinessSnapshotAccessService accessService,
            CurrentUserProvider currentUser,
            SafeArtifactStorageService storage,
            Clock clock) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper不能为空");
        this.sectionMapper = Objects.requireNonNull(sectionMapper, "sectionMapper不能为空");
        this.accessService = Objects.requireNonNull(accessService, "accessService不能为空");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser不能为空");
        this.storage = Objects.requireNonNull(storage, "storage不能为空");
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
    }

    /** 仅向任务所有者返回不含服务器路径的安全状态视图。 */
    public TaskView status(String taskId) {
        CompositeReportTask task = ownedTask(taskId);
        List<SectionView> sections = safeSections(taskId).stream()
                .map(section -> new SectionView(
                        section.getDatasetCode(), section.getStatus(), section.getDisplayOrder(),
                        section.getSafeMessage()))
                .toList();
        String visibleStatus = task.getExpiresAt() != null
                && !task.getExpiresAt().isAfter(LocalDateTime.now(clock))
                ? "EXPIRED" : task.getStatus();
        return new TaskView(task.getTaskId(), visibleStatus, task.getFormat(),
                task.getDataComplete(), task.getFileName(), task.getMimeType(), task.getFileSize(),
                task.getSafeErrorCode(), task.getSafeErrorMessage(), task.getExpiresAt(), sections);
    }

    /** 成功态且未过期的所有者任务，逐章节复核权限后才读取并校验文件。 */
    public DownloadArtifact download(String taskId) {
        CompositeReportTask task = ownedTask(taskId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (!DOWNLOADABLE_STATUSES.contains(task.getStatus()) || task.getExpiresAt() == null
                || !task.getExpiresAt().isAfter(now) || !safeArtifactMetadata(task)) {
            throw denied();
        }
        String authorization;
        try {
            authorization = currentUser.getRequiredAuthorization();
            BusinessSubjectType subjectType = BusinessSubjectType.valueOf(task.getSubjectType());
            List<CompositeReportSection> sections = safeSections(taskId);
            if (sections.isEmpty()) {
                throw denied();
            }
            for (CompositeReportSection section : sections) {
                Optional<AccessGrant> grant = accessService.reauthorize(new AccessCommand(
                        task.getTaskId(), task.getUserId(), task.getSessionId(), authorization,
                        Map.of(), section.getDatasetCode(), subjectType, task.getSubjectId(), Map.of()
                ));
                if (grant.isEmpty() || !Objects.equals(
                        section.getFieldPolicyChecksum(), grant.get().fieldPolicyChecksum())) {
                    throw denied();
                }
            }
            byte[] content = storage.readVerified(
                    task.getStoragePath(), Objects.requireNonNull(task.getFileSize()), task.getChecksum());
            return DownloadArtifact.takeOwnership(content, task.getFileName(), task.getMimeType());
        } catch (IOException | RuntimeException exception) {
            throw denied();
        }
    }

    /** 仅所有者可取消，实际状态竞争由Mapper XML条件更新保证。 */
    public void cancel(String taskId) {
        CompositeReportTask task = ownedTask(taskId);
        if (taskMapper.cancelIfCancellable(task.getTaskId(), task.getUserId()) != 1) {
            throw denied();
        }
    }

    private CompositeReportTask ownedTask(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw denied();
        }
        String userId;
        try {
            userId = currentUser.getRequiredUserId();
        } catch (RuntimeException exception) {
            throw denied();
        }
        CompositeReportTask task = taskMapper.selectByTaskId(taskId);
        if (task == null || !Objects.equals(task.getUserId(), userId)) {
            throw denied();
        }
        return task;
    }

    private List<CompositeReportSection> safeSections(String taskId) {
        List<CompositeReportSection> sections = sectionMapper.selectByTaskId(taskId);
        return sections == null ? List.of() : List.copyOf(sections);
    }

    private boolean safeArtifactMetadata(CompositeReportTask task) {
        String format = task.getFormat();
        String expectedMime = format == null ? null : MIME_TYPES.get(format);
        String extension = switch (Objects.toString(task.getFormat(), "")) {
            case "PDF" -> "pdf";
            case "DOCX" -> "docx";
            case "XLSX" -> "xlsx";
            default -> null;
        };
        String fileName = task.getFileName();
        String expectedPath = extension == null ? null
                : "reports/" + task.getTaskId() + "/report." + extension;
        return expectedMime != null
                && Objects.equals(expectedMime, task.getMimeType())
                && fileName != null && !fileName.isBlank() && fileName.length() <= 255
                && fileName.equals(fileName.trim())
                && fileName.chars().noneMatch(Character::isISOControl)
                && !fileName.contains("/") && !fileName.contains("\\")
                && fileName.toLowerCase(java.util.Locale.ROOT).endsWith('.' + extension)
                && Objects.equals(expectedPath, task.getStoragePath())
                && task.getFileSize() != null && task.getFileSize() > 0
                && task.getFileSize() <= 100L * 1024 * 1024
                && task.getChecksum() != null
                && task.getChecksum().matches("[0-9a-f]{64}");
    }

    private BusinessException denied() {
        return new BusinessException(404, GENERIC_DENIAL);
    }

    /** 下载内容及安全响应元数据。 */
    public static final class DownloadArtifact {

        private final byte[] content;
        private final String fileName;
        private final String mimeType;

        /** 测试和外部构造入口复制输入，避免调用方后续修改内容。 */
        public DownloadArtifact(byte[] content, String fileName, String mimeType) {
            this(content, fileName, mimeType, false);
        }

        private DownloadArtifact(
                byte[] content,
                String fileName,
                String mimeType,
                boolean takeOwnership) {
            byte[] required = Objects.requireNonNull(content, "content不能为空");
            this.content = takeOwnership ? required : required.clone();
            this.fileName = fileName;
            this.mimeType = mimeType;
        }

        /** 生产读取结果仅在服务内部移交所有权，避免再复制整份报告。 */
        static DownloadArtifact takeOwnership(byte[] content, String fileName, String mimeType) {
            return new DownloadArtifact(content, fileName, mimeType, true);
        }

        /** 测试读取始终返回副本，生产HTTP输出应使用writeTo。 */
        public byte[] content() {
            return content.clone();
        }

        public String fileName() {
            return fileName;
        }

        public String mimeType() {
            return mimeType;
        }

        public long contentLength() {
            return content.length;
        }

        /** 将内部唯一字节数组直接写向响应流。 */
        public void writeTo(OutputStream outputStream) throws IOException {
            Objects.requireNonNull(outputStream, "outputStream不能为空").write(content);
        }
    }

    /** 报告任务安全状态视图，不包含存储路径、校验和和身份字段。 */
    public record TaskView(
            String taskId,
            String status,
            String format,
            Boolean dataComplete,
            String fileName,
            String mimeType,
            Long fileSize,
            String safeErrorCode,
            String safeErrorMessage,
            LocalDateTime expiresAt,
            List<SectionView> sections) {
    }

    /** 报告章节安全状态视图，不包含快照引用和权限校验和。 */
    public record SectionView(String datasetCode, String status, Integer displayOrder, String safeMessage) {
    }
}
