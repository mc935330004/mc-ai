package org.example.ai.agent.common.file;

import org.example.ai.agent.common.config.StorageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 通用安全字节制品存储，只接受服务端生成的受控相对目标，不承载任何报告业务规则。
 * 生产环境必须通过操作系统ACL确保配置根目录只有应用运行账号可写；便携Java路径API
 * 无法完全消除恶意本地写入者在父目录检查与文件打开之间制造的竞态。
 */
@Service
public class SafeArtifactStorageService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final int MAX_ARTIFACT_BYTES = 100 * 1024 * 1024;

    private final Path root;

    @Autowired
    public SafeArtifactStorageService(StorageProperties properties) {
        this.root = Objects.requireNonNull(properties, "properties不能为空")
                .getReportDir().toAbsolutePath().normalize();
    }

    /**
     * 在同目录写临时文件并原子替换为最终文件，只返回相对路径和完整性元数据。
     */
    public StoredArtifact store(String relativePath, byte[] content) throws IOException {
        Objects.requireNonNull(content, "content不能为空");
        if (content.length == 0 || content.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("制品内容大小不合法");
        }
        Path relative = validateRelative(relativePath);
        String fileName = relative.getFileName().toString();
        Path target = resolveControlled(relative);
        createSafeDirectories(target.getParent());
        ensureNoSymbolicLinks(target.getParent());
        ensureNoSymbolicLinks(target);
        Path temporary = Files.createTempFile(target.getParent(), ".artifact-", ".tmp");
        try {
            try (SeekableByteChannel channel = Files.newByteChannel(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer source = ByteBuffer.wrap(content);
                while (source.hasRemaining()) {
                    channel.write(source);
                }
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("ARTIFACT_ATOMIC_MOVE_UNSUPPORTED", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new StoredArtifact(toPortablePath(relative), fileName, content.length, sha256(content));
    }

    /** 读取前重新校验受控路径、普通文件、大小和SHA-256。 */
    public byte[] readVerified(String relativePath, long expectedSize, String expectedChecksum)
            throws IOException {
        if (expectedSize <= 0 || expectedSize > MAX_ARTIFACT_BYTES
                || expectedChecksum == null || !SHA256.matcher(expectedChecksum).matches()) {
            throw new IllegalArgumentException("制品完整性元数据不合法");
        }
        Path relative = validateRelative(relativePath);
        Path target = resolveControlled(relative);
        ensureNoSymbolicLinks(target);
        byte[] content = new byte[(int) expectedSize];
        try (SeekableByteChannel channel = Files.newByteChannel(
                target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() != expectedSize) {
                throw new IOException("ARTIFACT_INTEGRITY_MISMATCH");
            }
            ByteBuffer destination = ByteBuffer.wrap(content);
            while (destination.hasRemaining()) {
                int read = channel.read(destination);
                if (read < 0) {
                    throw new IOException("ARTIFACT_INTEGRITY_MISMATCH");
                }
            }
            if (channel.read(ByteBuffer.allocate(1)) >= 0) {
                throw new IOException("ARTIFACT_INTEGRITY_MISMATCH");
            }
        } catch (java.nio.file.NoSuchFileException exception) {
            throw new IOException("ARTIFACT_NOT_FOUND", exception);
        }
        if (content.length != expectedSize || !MessageDigest.isEqual(
                sha256(content).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                expectedChecksum.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IOException("ARTIFACT_INTEGRITY_MISMATCH");
        }
        return content;
    }

    /** 删除前执行与读取相同的受控路径和符号链接检查。 */
    public boolean delete(String relativePath) throws IOException {
        Path target = resolveControlled(validateRelative(relativePath));
        ensureNoSymbolicLinks(target);
        return Files.deleteIfExists(target);
    }

    private Path validateRelative(String value) {
        if (value == null || value.isBlank() || containsControl(value) || value.contains("\\")) {
            throw new IllegalArgumentException("制品路径不合法");
        }
        Path relative;
        try {
            relative = Path.of(value);
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("制品路径不合法", exception);
        }
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("制品路径不合法");
        }
        for (Path part : relative) {
            if ("..".equals(part.toString()) || ".".equals(part.toString())) {
                throw new IllegalArgumentException("制品路径不合法");
            }
        }
        return relative.normalize();
    }

    private Path resolveControlled(Path relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("制品路径不合法");
        }
        return resolved;
    }

    private void createSafeDirectories(Path directory) throws IOException {
        Path current = root.getRoot();
        for (Path part : root) {
            current = current == null ? part : current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("ARTIFACT_SYMLINK_REJECTED");
            }
        }
        Files.createDirectories(root);
        current = root;
        for (Path part : root.relativize(directory)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("ARTIFACT_DIRECTORY_REJECTED");
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private void ensureNoSymbolicLinks(Path target) throws IOException {
        Path rootCursor = root.getRoot();
        for (Path part : root) {
            rootCursor = rootCursor == null ? part : rootCursor.resolve(part);
            if (Files.exists(rootCursor, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(rootCursor)) {
                throw new IOException("ARTIFACT_SYMLINK_REJECTED");
            }
        }
        Path current = root;
        Path relative = root.relativize(target);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw new IOException("ARTIFACT_SYMLINK_REJECTED");
            }
        }
    }

    private boolean containsControl(String value) {
        return value.chars().anyMatch(character -> Character.isISOControl(character));
    }

    private String toPortablePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK不支持SHA-256", exception);
        }
    }

    /** 受控制品元数据，绝不暴露服务器绝对路径。 */
    public record StoredArtifact(String relativePath, String fileName, long fileSize, String checksum) {
    }
}
