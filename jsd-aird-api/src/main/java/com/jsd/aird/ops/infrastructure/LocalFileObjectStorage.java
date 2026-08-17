package com.jsd.aird.ops.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;

import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development/test object storage that keeps staged files on the local disk.
 * MinIO remains the default provider; this implementation is opt-in through
 * {@code JSD_AIRD_STORAGE_PROVIDER=local}.
 */
@Component
@ConditionalOnProperty(name = "app.storage.provider", havingValue = "local")
public class LocalFileObjectStorage implements ObjectStorage {

    private final Path root;

    public LocalFileObjectStorage(
            @Value("${app.storage.local-root:${java.io.tmpdir}/jsd-aird-storage}") String root
    ) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException exception) {
            throw new IllegalStateException("本地对象存储目录不可用: " + this.root, exception);
        }
    }

    @Override
    public void put(String objectKey, InputStream source, long size, String contentType) {
        var target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "本地对象存储写入失败");
        }
    }

    @Override
    public StoredObject get(String objectKey) {
        var target = resolve(objectKey);
        try {
            return new StoredObject(Files.newInputStream(target), Files.size(target), "application/octet-stream");
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "本地对象存储读取失败");
        }
    }

    @Override
    public Optional<String> presignedGetUrl(String objectKey, Duration expiry) {
        return Optional.empty();
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.INTERNAL_ERROR, "本地对象存储清理失败");
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "对象存储键不能为空");
        }
        var target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "对象存储键非法");
        }
        return target;
    }
}
