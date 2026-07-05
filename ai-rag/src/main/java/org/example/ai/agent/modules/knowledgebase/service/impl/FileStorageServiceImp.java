package org.example.ai.agent.modules.knowledgebase.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.example.ai.agent.common.config.FtpStorageProperties;
import org.example.ai.agent.modules.knowledgebase.service.FileStorageService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 文件存储服务实现
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileStorageServiceImp implements FileStorageService {

    private final FtpStorageProperties properties;
    @Override
    public String saveKnowledgeBase(MultipartFile file) {
        String storageKey = buildStorageKey(file.getOriginalFilename());
        String tempKey = storageKey + ".tmp";
        FTPClient ftp = connect();
        try {
            ensureParentDirectories(ftp, tempKey);
            boolean uploaded = ftp.storeFile(tempKey, file.getInputStream());
            if (!uploaded) {
                throw new IllegalStateException("FTP upload failed: " + ftp.getReplyString());
            }
            boolean renamed = ftp.rename(tempKey, storageKey);
            if (!renamed) {
                ftp.deleteFile(tempKey);
                throw new IllegalStateException("FTP rename failed: " + ftp.getReplyString());
            }
            return storageKey;
        } catch (IOException e) {
            throw new IllegalStateException("Save file to FTP failed", e);
        } finally {
            disconnect(ftp);
        }
    }

    @Override
    public byte[] downloadFile(String storageKey) {
        FTPClient ftp = connect();
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            boolean downloaded = ftp.retrieveFile(storageKey, output);
            if (!downloaded) {
                throw new IllegalStateException("FTP download failed: " + ftp.getReplyString());
            }
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Download file from FTP failed", e);
        } finally {
            disconnect(ftp);
        }
    }

    @Override
    public void deleteFile(String storageKey) {
        FTPClient ftp = connect();
        try {
            ftp.deleteFile(storageKey);
        } catch (IOException e) {
            throw new IllegalStateException("Delete FTP file failed", e);
        } finally {
            disconnect(ftp);
        }
    }

    /**
     * 构建存储文件名
     * @param originalFilename
     * @return
     */
    private String buildStorageKey(String originalFilename) {
        String ext = "";
        if (originalFilename != null) {
            int dot = originalFilename.lastIndexOf('.');
            if (dot >= 0) {
                ext = originalFilename.substring(dot);
            }
        }
        LocalDate now = LocalDate.now();
        return "%s/%d/%s%s".formatted(
                properties.getBaseDir(),
                now.getYear(),
                UUID.randomUUID(),
                ext
        );
    }

    /**
     * 连接到 FTP 服务器
     * @return
     */
    private FTPClient connect() {
        FTPClient ftp = new FTPClient();
        try {
            ftp.setConnectTimeout(properties.getConnectTimeoutMs());
            ftp.connect(properties.getHost(), properties.getPort());

            boolean loggedIn = ftp.login(properties.getUsername(), properties.getPassword());
            if (!loggedIn) {
                throw new IllegalStateException("FTP login failed: " + ftp.getReplyString());
            }
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            ftp.enterLocalPassiveMode();
            ftp.setDataTimeout(properties.getDataTimeoutMs());
            return ftp;
        } catch (IOException e) {
            disconnect(ftp);
            throw new IllegalStateException("Connect FTP failed", e);
        }
    }

    /**
     * 确保父级目录存在
     * @param ftp
     * @param storageKey
     * @throws IOException
     */
    private void ensureParentDirectories(FTPClient ftp, String storageKey) throws IOException {
        int lastSlash = storageKey.lastIndexOf('/');
        if (lastSlash <= 0) {
            return;
        }

        String parent = storageKey.substring(0, lastSlash);
        String[] parts = parent.split("/");

        String path = "";
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            path += "/" + part;
            ftp.makeDirectory(path);
        }
    }

    /**
     * 断开连接
     * @param ftp
     */
    private void disconnect(FTPClient ftp) {
        if (ftp == null || !ftp.isConnected()) {
            return;
        }
        try {
            ftp.logout();
            ftp.disconnect();
        } catch (IOException ignored) {
        }
    }
}
