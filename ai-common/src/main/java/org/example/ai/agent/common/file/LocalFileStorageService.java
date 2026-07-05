package org.example.ai.agent.common.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.common.config.StorageProperties;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 本地文件存储服务，作为 RAG 上传 MVP 阶段对 RustFS/S3 的轻量替代。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalFileStorageService {

    private static final DateTimeFormatter DATE_PATH_FORMAT = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final int MAX_SAFE_FILENAME_LENGTH = 120;

    private final StorageProperties storageProperties;

    /**
     * 保存知识库原始文件到本地磁盘，并返回相对存储路径。
     */
    public String saveKnowledgeBase(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择要上传的知识库文件");
        }

        String storagePath = generateStoragePath(file.getOriginalFilename());
        Path targetPath = resolveStoragePath(storagePath);

        try {
            Files.createDirectories(targetPath.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("知识库文件已保存到本地: {}", targetPath);
            return storagePath;
        } catch (IOException e) {
            log.error("保存知识库文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件保存失败");
        }
    }

    /**
     * 根据数据库中保存的相对路径读取原始文件内容。
     */
    public byte[] downloadFile(String storagePath) {
        Path targetPath = resolveStoragePath(storagePath);
        if (!Files.isRegularFile(targetPath)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文件不存在");
        }

        try {
            return Files.readAllBytes(targetPath);
        } catch (IOException e) {
            log.error("读取本地文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DOWNLOAD_FAILED, "文件读取失败");
        }
    }

    /**
     * 删除本地原始文件；路径为空时直接跳过，方便删除知识库记录时容错。
     */
    public void deleteFile(String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            return;
        }

        Path targetPath = resolveStoragePath(storagePath);
        try {
            Files.deleteIfExists(targetPath);
            log.info("本地文件已删除: {}", targetPath);
        } catch (IOException e) {
            log.error("删除本地文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.STORAGE_DELETE_FAILED, "文件删除失败");
        }
    }

    /**
     * 将相对存储路径转换为绝对路径，主要用于日志、调试和后续下载接口。
     */
    public Path getAbsolutePath(String storagePath) {
        return resolveStoragePath(storagePath);
    }

    /**
     * 生成日期分目录下的唯一文件名，避免同名文件覆盖。
     */
    private String generateStoragePath(String originalFilename) {
        String datePath = LocalDate.now().format(DATE_PATH_FORMAT);
        String uniqueName = UUID.randomUUID().toString().replace("-", "");
        String safeFilename = sanitizeFilename(originalFilename);
        return datePath + "/" + uniqueName + "_" + safeFilename;
    }

    /**
     * 清理上传文件名中的路径、控制字符和 Windows 非法字符。
     */
    private String sanitizeFilename(String originalFilename) {
        String filename = StringUtils.hasText(originalFilename)
                ? Paths.get(originalFilename).getFileName().toString()
                : "unknown";

        String safeFilename = filename
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_")
                .replaceAll("\\s+", "_")
                .strip();

        if (!StringUtils.hasText(safeFilename)) {
            safeFilename = "unknown";
        }

        if (safeFilename.length() <= MAX_SAFE_FILENAME_LENGTH) {
            return safeFilename;
        }

        int dotIndex = safeFilename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < safeFilename.length() - 1) {
            String extension = safeFilename.substring(dotIndex);
            int baseLength = Math.max(1, MAX_SAFE_FILENAME_LENGTH - extension.length());
            return safeFilename.substring(0, baseLength) + extension;
        }
        return safeFilename.substring(0, MAX_SAFE_FILENAME_LENGTH);
    }

    /**
     * 解析相对路径并校验最终路径必须位于配置的存储根目录下，防止路径穿越。
     */
    private Path resolveStoragePath(String storagePath) {
        if (!StringUtils.hasText(storagePath)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件存储路径不能为空");
        }

        Path rootDir = storageProperties.getKnowledgeBaseDir().toAbsolutePath().normalize();
        Path relativePath = Paths.get(storagePath.replace("\\", "/")).normalize();
        if (relativePath.isAbsolute() || relativePath.startsWith("..")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "非法文件存储路径");
        }

        Path targetPath = rootDir.resolve(relativePath).normalize();
        if (!targetPath.startsWith(rootDir)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "非法文件存储路径");
        }
        return targetPath;
    }
}
