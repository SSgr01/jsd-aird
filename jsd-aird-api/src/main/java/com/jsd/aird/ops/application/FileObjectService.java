package com.jsd.aird.ops.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import com.jsd.aird.ops.application.port.FileObjectRepository;
import com.jsd.aird.ops.application.port.FileStorageFacade;
import com.jsd.aird.ops.application.port.ObjectStorage;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import com.jsd.aird.shared.security.ActorContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FileObjectService implements FileStorageFacade {

    private static final long MAX_KNOWLEDGE_UPLOAD_BYTES = 512L * 1024 * 1024;

    private static final long MAX_DOCUMENT_UPLOAD_BYTES = 200L * 1024 * 1024;

    private static final long MAX_SPC_UPLOAD_BYTES = 100L * 1024 * 1024;


    private static final Set<String> BLOCKED_OOXML_PARTS = Set.of(
            "vbaproject.bin",
            "activex"
    );

    private final ObjectStorage objectStorage;
    private final FileObjectRepository repository;
    private final String bucket;

    public FileObjectService(
            ObjectStorage objectStorage,
            FileObjectRepository repository,
            @Value("${app.storage.bucket}") String bucket
    ) {
        this.objectStorage = objectStorage;
        this.repository = repository;
        this.bucket = bucket;
    }

    @Transactional
    public StagedFile stage(
            String originalName,
            String contentType,
            String kind,
            InputStream source
    ) {
        var actor = ActorContext.required();
        Path temporary = null;
        try {
            temporary = Files.createTempFile("jsd-aird-upload-", ".staged");
            var digest = MessageDigest.getInstance("SHA-256");
            try (var digestStream = new DigestInputStream(source, digest)) {
                Files.copy(digestStream, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            var size = Files.size(temporary);
            var sha256 = hex(digest.digest());
            if ("KNOWLEDGE".equalsIgnoreCase(kind) && size > MAX_KNOWLEDGE_UPLOAD_BYTES) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "知识文件超过 512MB 限制");
            }
            if ("PROJECT_DOCUMENT".equalsIgnoreCase(kind) && size > MAX_DOCUMENT_UPLOAD_BYTES) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "项目文档超过 200MB 限制");
            }
            if ("EXPERIMENT_SOURCE".equalsIgnoreCase(kind) && size > MAX_DOCUMENT_UPLOAD_BYTES) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "实验文档超过 200MB 限制");
            }
            if ("SPC_CHART".equalsIgnoreCase(kind) && size > MAX_SPC_UPLOAD_BYTES) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "图谱文件超过 100MB 限制");
            }
            validateSecurity(temporary, originalName, kind);
            var effectiveContentType = FileContentTypeResolver.resolve(temporary, originalName, contentType);

            var id = UUID.randomUUID();
            var safeName = sanitizeFileName(originalName);
            var objectKey = actor.organizationId() + "/staged/" + id + "/" + safeName;
            try (var uploadStream = Files.newInputStream(temporary)) {
                objectStorage.put(objectKey, uploadStream, size, effectiveContentType);
            }
            repository.insert(new FileObjectRepository.NewFileObject(
                    id,
                    actor.organizationId(),
                    bucket,
                    objectKey,
                    safeName,
                    effectiveContentType,
                    size,
                    sha256,
                    actor.userId()
            ));
            return new StagedFile(id, safeName, effectiveContentType, size, sha256, "STAGED");
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "文件暂存失败");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The staged-object cleanup worker remains the final recovery path.
                }
            }
        }
    }

    public DownloadedFile download(UUID fileId) {
        var actor = ActorContext.required();
        var file = repository.find(actor.organizationId(), fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "文件不存在"));
        if ("DELETED".equals(file.status())) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "文件已删除");
        }
        var stored = objectStorage.get(file.objectKey());
        return new DownloadedFile(
                file.originalName(),
                file.contentType(),
                file.size(),
                stored
        );
    }

    @Override
    public FileStorageFacade.StagedFile stageFile(
            String originalName, String contentType, String kind, InputStream source
    ) {
        var staged = stage(originalName, contentType, kind, source);
        return new FileStorageFacade.StagedFile(
                staged.fileId(), staged.originalName(), staged.contentType(), staged.size(), staged.sha256(), staged.status()
        );
    }

    @Override
    public FileStorageFacade.StoredFile open(UUID organizationId, UUID fileId) {
        var file = repository.find(organizationId, fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "文件不存在"));
        if ("DELETED".equals(file.status())) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "文件已删除");
        }
        var stored = objectStorage.get(file.objectKey());
        return new FileStorageFacade.StoredFile(
                fileId,
                file.originalName(),
                file.contentType(),
                file.size(),
                file.sha256(),
                stored.stream()
        );
    }

    @Override
    public Optional<String> presignedUrl(UUID organizationId, UUID fileId, Duration expiry) {
        var file = repository.find(organizationId, fileId)
                .orElseThrow(() -> new ApiException(ApiErrorCode.NOT_FOUND, "文件不存在"));
        if ("DELETED".equals(file.status())) return Optional.empty();
        return objectStorage.presignedGetUrl(file.objectKey(), expiry);
    }

    @Override
    public void activate(UUID fileId) {
        repository.activate(fileId);
    }

    private void validateSecurity(Path file, String originalName, String kind) throws IOException {
        if ("SPC_CHART".equalsIgnoreCase(kind)) {
            validateSpectrumFile(file, originalName);
            return;
        }
        if ("DATA_SOURCE".equalsIgnoreCase(kind)) {
            var lowerName = originalName.toLowerCase(Locale.ROOT);
            if (!lowerName.endsWith(".xls") && !lowerName.endsWith(".xlsx") && !lowerName.endsWith(".csv")) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "数据中心仅接受 XLS、XLSX 或 CSV 文件");
            }
            return;
        }
        if (!"OFFICE".equalsIgnoreCase(kind) && !"TEMPLATE_SOURCE".equalsIgnoreCase(kind)) {
            return;
        }
        var lowerName = originalName.toLowerCase(Locale.ROOT);
        var xlsx = lowerName.endsWith(".xlsx");
        var docx = lowerName.endsWith(".docx");
        if ("TEMPLATE_SOURCE".equalsIgnoreCase(kind)
                && (lowerName.endsWith(".xls") || lowerName.endsWith(".csv") || lowerName.endsWith(".doc"))) {
            // Legacy template formats are validated and converted by the tpl
            // normalization endpoint before an import job is created.
            return;
        }
        if (!xlsx && !docx) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "只接受 XLSX 或 DOCX OOXML 文件");
        }
        var entries = new HashSet<String>();
        try (var input = Files.newInputStream(file); var zip = new ZipInputStream(input)) {
            var entry = zip.getNextEntry();
            if (entry == null) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件不是有效的 OOXML 包");
            }
            while (entry != null) {
                var name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
                entries.add(name);
                for (String blocked : BLOCKED_OOXML_PARTS) {
                    if (name.contains(blocked)) {
                        throw new ApiException(
                                ApiErrorCode.BAD_REQUEST,
                                "检测到当前版本不支持的宏、ActiveX、嵌入对象或外部链接"
                        );
                    }
                }
                entry = zip.getNextEntry();
            }
        }
        if (!entries.contains("[content_types].xml")) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "OOXML 包缺少 [Content_Types].xml");
        }
        if (xlsx && !entries.contains("xl/workbook.xml")) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "XLSX 文件缺少工作簿内容");
        }
        if (docx && !entries.contains("word/document.xml")) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "DOCX 文件缺少正文内容");
        }
    }

    private void validateSpectrumFile(Path file, String originalName) throws IOException {
        var lowerName = (originalName == null ? "" : originalName).toLowerCase(Locale.ROOT);
        if (!(lowerName.endsWith(".pdf") || lowerName.endsWith(".png") || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg") || lowerName.endsWith(".tif") || lowerName.endsWith(".tiff"))) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "图谱中心仅接受 PDF、PNG、JPG、JPEG、TIF 或 TIFF 文件");
        }
        if (lowerName.endsWith(".pdf")) {
            try (var input = Files.newInputStream(file)) {
                var header = input.readNBytes(5);
                if (header.length != 5 || header[0] != '%' || header[1] != 'P' || header[2] != 'D'
                        || header[3] != 'F' || header[4] != '-') {
                    throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件不是有效的 PDF 图谱");
                }
            }
            return;
        }
        if (ImageIO.read(file.toFile()) == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件不是有效的图片图谱");
        }
    }

    private String sanitizeFileName(String originalName) {
        var value = StringUtils.hasText(originalName) ? originalName.trim() : "unnamed.bin";
        return value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    private String hex(byte[] value) {
        var result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(String.format("%02x", item));
        }
        return result.toString();
    }

    public record StagedFile(
            UUID fileId,
            String originalName,
            String contentType,
            long size,
            String sha256,
            String status
    ) {
    }

    public record DownloadedFile(
            String originalName,
            String contentType,
            long size,
            ObjectStorage.StoredObject storedObject
    ) {
    }
}
