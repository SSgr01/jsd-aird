package com.jsd.aird.ai.formula.application;

import com.jsd.aird.ai.formula.api.FormulaModelContracts.ArtifactReadRef;
import com.jsd.aird.ai.formula.api.FormulaModelContracts.ArtifactWriteRef;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Duration;

@Component
public class FormulaModelArtifactStore {
    private static final Duration READ_EXPIRY = Duration.ofMinutes(15);
    private static final Duration WRITE_EXPIRY = Duration.ofHours(4);
    private final ObjectStorage storage;

    public FormulaModelArtifactStore(ObjectStorage storage) {
        this.storage = storage;
    }

    public StoredArtifact put(String objectKey, byte[] data, String contentType) {
        try (var existing = storage.get(objectKey); var output = new ByteArrayOutputStream()) {
            existing.stream().transferTo(output);
            var bytes = output.toByteArray();
            if (!sha256(bytes).equals(sha256(data))) {
                throw new ApiException(ApiErrorCode.RESOURCE_CONFLICT, "不可变模型制品已存在且内容不同");
            }
            return new StoredArtifact(objectKey, sha256(bytes), bytes.length, contentType);
        } catch (ApiException exception) {
            if (exception.errorCode() != ApiErrorCode.FILE_NOT_READY) throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "无法检查模型制品是否已存在");
        }
        storage.put(objectKey, new ByteArrayInputStream(data), data.length, contentType);
        return new StoredArtifact(objectKey, sha256(data), data.length, contentType);
    }

    public ArtifactReadRef readRef(String name, StoredArtifact artifact) {
        var url = storage.presignedGetUrl(artifact.objectKey(), READ_EXPIRY)
                .orElseThrow(() -> new ApiException(ApiErrorCode.AI_MODEL_NOT_CONFIGURED,
                        "对象存储未配置可供计算服务读取的签名地址"));
        return new ArtifactReadRef(name, url, artifact.sha256());
    }

    public ArtifactReadRef readRef(String name, String objectKey, String sha256) {
        var url = storage.presignedGetUrl(objectKey, READ_EXPIRY)
                .orElseThrow(() -> new ApiException(ApiErrorCode.AI_MODEL_NOT_CONFIGURED,
                        "对象存储未配置可供计算服务读取的签名地址"));
        return new ArtifactReadRef(name, url, sha256);
    }

    public ArtifactWriteRef writeRef(String objectKey, String contentType) {
        var url = storage.presignedPutUrl(objectKey, WRITE_EXPIRY)
                .orElseThrow(() -> new ApiException(ApiErrorCode.AI_MODEL_NOT_CONFIGURED,
                        "对象存储未配置可供计算服务写入的签名地址"));
        return new ArtifactWriteRef(url, contentType);
    }

    public StoredArtifact verify(String objectKey, String expectedHash, String contentType) {
        try (var object = storage.get(objectKey); var output = new ByteArrayOutputStream()) {
            object.stream().transferTo(output);
            var bytes = output.toByteArray();
            var actual = sha256(bytes);
            if (!actual.equals(expectedHash)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "计算制品哈希校验失败");
            }
            return new StoredArtifact(objectKey, actual, bytes.length, contentType);
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "计算制品无法读取");
        }
    }

    public byte[] read(String objectKey, String expectedHash) {
        try (var object = storage.get(objectKey); var output = new ByteArrayOutputStream()) {
            object.stream().transferTo(output);
            var bytes = output.toByteArray();
            if (!sha256(bytes).equals(expectedHash)) {
                throw new ApiException(ApiErrorCode.INVALID_SCHEMA, "计算制品哈希校验失败");
            }
            return bytes;
        } catch (ApiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ApiException(ApiErrorCode.FILE_NOT_READY, "计算制品无法读取");
        }
    }

    public static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256不可用", exception);
        }
    }

    public record StoredArtifact(String objectKey, String sha256, long size, String contentType) {}
}
