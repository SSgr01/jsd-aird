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

    private com.jsd.aird.shared.resource.CapacityProperties capacity=com.jsd.aird.shared.resource.CapacityProperties.defaults();
    private com.jsd.aird.shared.resource.TemporarySpace temporarySpace;
    private org.springframework.transaction.support.TransactionTemplate stagingTransaction;

    @org.springframework.beans.factory.annotation.Autowired
    void configureCapacity(com.jsd.aird.shared.resource.CapacityProperties limits,
                           com.jsd.aird.shared.resource.TemporarySpace temporary,
                           org.springframework.transaction.PlatformTransactionManager transactions){
        capacity=limits;temporarySpace=temporary;
        stagingTransaction=new org.springframework.transaction.support.TransactionTemplate(transactions);
        stagingTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private long uploadLimit(String kind){return switch(kind==null?"":kind.toUpperCase(java.util.Locale.ROOT)){
        case "KNOWLEDGE"->Math.min(capacity.fileBytes(),MAX_KNOWLEDGE_UPLOAD_BYTES);
        case "PROJECT_DOCUMENT","EXPERIMENT_SOURCE"->Math.min(capacity.fileBytes(),MAX_DOCUMENT_UPLOAD_BYTES);
        case "SPC_CHART"->Math.min(capacity.fileBytes(),MAX_SPC_UPLOAD_BYTES);
        default->capacity.fileBytes();
    };}

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

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public StagedFile stage(
            String originalName,
            String contentType,
            String kind,
            InputStream source
    ) {
        var actor = ActorContext.required();
        return stageFor(actor.organizationId(), actor.userId(), originalName, contentType, kind, source, false);
    }

    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @Override
    public FileStorageFacade.StagedFile stageDerived(UUID organizationId, UUID actorId, String originalName,
                                                      String contentType, String kind, InputStream source) {
        if (organizationId == null || actorId == null) {
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "解析产物缺少组织或创建人");
        }
        var staged = stageFor(organizationId, actorId, originalName, contentType, kind, source, true);
        return new FileStorageFacade.StagedFile(staged.fileId(), staged.originalName(), staged.contentType(),
                staged.size(), staged.sha256(), staged.status());
    }

    private StagedFile stageFor(UUID organizationId, UUID actorId, String originalName, String contentType,
                                String kind, InputStream source, boolean derived) {
        Path temporary = null;
        String uploadedKey=null;
        if(temporarySpace==null)temporarySpace=new com.jsd.aird.shared.resource.TemporarySpace(capacity.temporaryBytes(),Path.of(System.getProperty("java.io.tmpdir"),"jsd-aird-bounded"));
        var reservation=temporarySpace.reserve(uploadLimit(kind));
        try {
            temporary = temporarySpace.create();
            var digest = MessageDigest.getInstance("SHA-256");
            try (source;var output=Files.newOutputStream(temporary)) {
                com.jsd.aird.shared.resource.BoundedStreams.copy(source,output,uploadLimit(kind),digest);
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
            if (!derived) validateSecurity(temporary, originalName, kind);
            var effectiveContentType = FileContentTypeResolver.resolve(temporary, originalName, contentType);

            var id = UUID.randomUUID();
            var safeName = sanitizeFileName(originalName);
            var objectKey = organizationId + "/" + (derived ? "derived" : "staged") + "/" + id + "/" + safeName;
            uploadedKey=objectKey;
            try (var uploadStream = Files.newInputStream(temporary)) {
                objectStorage.put(objectKey, uploadStream, size, effectiveContentType);
            }
            var insert=new FileObjectRepository.NewFileObject(
                    id,
                    organizationId,
                    bucket,
                    objectKey,
                    safeName,
                    effectiveContentType,
                    size,
                    sha256,
                    actorId
            );
            if(stagingTransaction==null)repository.insert(insert);
            else stagingTransaction.executeWithoutResult(status->repository.insert(insert));
            uploadedKey=null;
            return new StagedFile(id, safeName, effectiveContentType, size, sha256, "STAGED");
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new ApiException(ApiErrorCode.SNAPSHOT_PERSIST_FAILED, "文件暂存失败");
        } finally {
            if(uploadedKey!=null)try{objectStorage.delete(uploadedKey);}catch(RuntimeException ignored){ }
            if (temporary != null) {
                temporarySpace.deleteAndRelease(temporary,reservation);
            }else reservation.close();
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
                new ObjectStorage.StoredObject(com.jsd.aird.shared.resource.BoundedStreams.limit(stored.stream(),capacity.fileBytes()),stored.size(),stored.contentType())
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
                com.jsd.aird.shared.resource.BoundedStreams.limit(stored.stream(),capacity.fileBytes())
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
            validateDataSource(file, originalName);
            return;
        }
        if ("AI_MODELING_CATALOG".equalsIgnoreCase(kind)) {
            var lowerName = originalName.toLowerCase(Locale.ROOT);
            if (!lowerName.endsWith(".xls") && !lowerName.endsWith(".xlsx")) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "指标清单仅接受 XLS 或 XLSX 文件");
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
        validateOoxmlPackage(file, xlsx, docx);
    }

    private void validateDataSource(Path file, String originalName) throws IOException {
        var lowerName = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        var xlsx = lowerName.endsWith(".xlsx");
        var docx = lowerName.endsWith(".docx");
        if (xlsx || docx) {
            validateOoxmlPackage(file, xlsx, docx);
            return;
        }
        if (lowerName.endsWith(".xls") || lowerName.endsWith(".doc")) {
            validateOleFile(file, lowerName.endsWith(".xls") ? "XLS" : "DOC");
            return;
        }
        if (lowerName.endsWith(".pdf")) {
            validatePdfFile(file);
            return;
        }
        if (lowerName.endsWith(".csv")) {
            if (Files.size(file) == 0) throw new ApiException(ApiErrorCode.BAD_REQUEST, "CSV 文件为空");
            return;
        }
        throw new ApiException(ApiErrorCode.BAD_REQUEST,
                "数据中心仅接受 XLS、XLSX、CSV、DOC、DOCX 或 PDF 文件");
    }

    private void validateOoxmlPackage(Path file, boolean xlsx, boolean docx) throws IOException {
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

    private void validateOleFile(Path file, String format) throws IOException {
        var expected = new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        try (var input = Files.newInputStream(file)) {
            if (!java.util.Arrays.equals(input.readNBytes(expected.length), expected)) {
                throw new ApiException(ApiErrorCode.BAD_REQUEST,
                        format + " 文件内容无效或已损坏，请上传真实的 Office 文件");
            }
        }
    }

    private void validatePdfFile(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            var header = input.readNBytes(5);
            if (header.length != 5 || header[0] != '%' || header[1] != 'P' || header[2] != 'D'
                    || header[3] != 'F' || header[4] != '-') {
                throw new ApiException(ApiErrorCode.BAD_REQUEST,
                        "PDF 文件头无效或文件已损坏，请上传真实的 PDF 文件");
            }
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
