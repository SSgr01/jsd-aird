package com.jsd.aird.kb.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.kb.api.PrivateDocumentParsingFacade;
import com.jsd.aird.kb.domain.DocumentParser;
import com.jsd.aird.kb.domain.FileSafetyScanner;
import com.jsd.aird.shared.error.ApiErrorCode;
import com.jsd.aird.shared.error.ApiException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PrivateDocumentParsingService implements PrivateDocumentParsingFacade {
    private final List<DocumentParser> parsers;
    private final FileSafetyScanner scanner;
    private final SystemParsingPolicy policy;
    private final StructuredDocumentCodec codec;
    private final ObjectMapper mapper;

    public PrivateDocumentParsingService(List<DocumentParser> parsers, FileSafetyScanner scanner,
            SystemParsingPolicy policy, StructuredDocumentCodec codec, ObjectMapper mapper) {
        this.parsers = parsers; this.scanner = scanner; this.policy = policy; this.codec = codec; this.mapper = mapper;
    }

    @Override public JsonNode parse(UUID organizationId, UUID actorId, UUID fileId, String name,
            String contentType, long size, InputStream input) {
        try {
            if (size < 1 || size > 10 * 1024 * 1024) throw new ApiException(ApiErrorCode.BAD_REQUEST);
            var bytes = input.readNBytes(10 * 1024 * 1024 + 1);
            if (bytes.length != size) throw new ApiException(ApiErrorCode.BAD_REQUEST);
            var scan = scanner.scan(new ByteArrayInputStream(bytes), name, contentType, size);
            if (scan.status() != FileSafetyScanner.ScanResult.Status.SAFE)
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "文件未通过现有安全检查");
            var parser = parsers.stream().filter(item -> item.supports(name, contentType)).findFirst()
                .orElseThrow(() -> new ApiException(ApiErrorCode.BAD_REQUEST, "不支持的私有资料格式"));
            if (!parser.isConfigured()) throw new ApiException(ApiErrorCode.AI_NOT_CONFIGURED, parser.unavailableReason());
            var configuration = policy.forFile(name, contentType);
            var parsed = parser.parse(new ByteArrayInputStream(bytes), name, new DocumentParser.ParseContext(
                organizationId, actorId, fileId, contentType, size, configuration.ocrMode(), configuration.allowAgentFallback()));
            var result = mapper.createObjectNode().put("parser_version", parsed.parserVersion());
            var nodes = result.putArray("nodes");
            var structured = codec.initialize(parsed.blocks());
            for (var block : structured.sourceNodes()) {
                var node = mapper.createObjectNode().put("key", block.sourceNodeKey().toString()).put("text", block.rawText());
                node.set("anchor", block.sourceAnchor());
                nodes.add(node);
            }
            result.set("source_tables", mapper.valueToTree(parsed.sourceTables()));
            if (nodes.isEmpty() || result.toString().length() > 2_000_000)
                throw new ApiException(ApiErrorCode.BAD_REQUEST, "解析结果为空或超过私有资料预算");
            return result;
        } catch (ApiException error) { throw error; }
        catch (Exception error) { throw new ApiException(ApiErrorCode.BAD_REQUEST, "私有资料解析失败"); }
    }
}
