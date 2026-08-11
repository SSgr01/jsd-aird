package com.jsd.aird.ops.infrastructure;

import java.io.InputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MinioObjectStorage implements ObjectStorage {

    private final MinioClient client;
    private final MinioClient publicClient;
    private final String bucket;

    public MinioObjectStorage(
            @Value("${app.storage.endpoint}") String endpoint,
            @Value("${app.storage.public-endpoint:}") String publicEndpoint,
            @Value("${app.storage.access-key}") String accessKey,
            @Value("${app.storage.secret-key}") String secretKey,
            @Value("${app.storage.bucket}") String bucket
    ) {
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
        this.publicClient = publicEndpoint == null || publicEndpoint.isBlank() ? null : MinioClient.builder()
                .endpoint(publicEndpoint.strip().replaceAll("/+$", ""))
                .credentials(accessKey, secretKey)
                .build();
        this.bucket = bucket;
    }

    private void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO bucket is not available: " + bucket, exception);
        }
    }

    @Override
    public void put(String objectKey, InputStream source, long size, String contentType) {
        try {
            ensureBucket();
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .contentType(contentType)
                    .stream(source, size, -1)
                    .build());
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "对象存储写入失败");
        }
    }

    @Override
    public StoredObject get(String objectKey) {
        try {
            var stream = client.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return new StoredObject(stream, -1, "application/octet-stream");
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "对象存储读取失败");
        }
    }

    @Override
    public Optional<String> presignedGetUrl(String objectKey, Duration expiry) {
        if (publicClient == null || objectKey == null || objectKey.isBlank()) return Optional.empty();
        try {
            var seconds = Math.max(60, Math.min(7 * 24 * 60 * 60, expiry == null ? 900 : expiry.toSeconds()));
            return Optional.of(publicClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry((int) seconds, TimeUnit.SECONDS)
                    .build()));
        } catch (Exception exception) {
            throw new IllegalStateException("MinIO 公网预签名 URL 生成失败", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.INTERNAL_ERROR, "对象存储清理失败");
        }
    }
}
