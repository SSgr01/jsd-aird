package com.jsd.aird.spc.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jsd.aird.spc.application.port.SpectrumRepository;
import com.jsd.aird.shared.business.*;
import com.jsd.aird.shared.error.*;
import com.jsd.aird.shared.security.ActorContext;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.jsd.aird.shared.business.BusinessViews.*;

@Service
public class BusinessSpectrumReader implements BusinessReadPort {
    private final SpectrumRepository spectra;
    private final SpectrumService files;
    private final ObjectMapper json;
    public BusinessSpectrumReader(SpectrumRepository spectra, SpectrumService files, ObjectMapper json) { this.spectra = spectra; this.files = files; this.json = json; }
    public Set<String> resourceTypes() { return Set.of("SPECTRUM", "SPECTRUM_ANALYSIS"); }
    public Set<String> filters(String type) { return "SPECTRUM".equals(type) ? Set.of("query", "category_id", "status") : Set.of(); }
    public Page references(String type, ObjectNode filter, int page, int size) {
        if (!"SPECTRUM".equals(type)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "历史谱图分析请按已有运行id读取，不提供全局历史搜索");
        var actor = ActorContext.required();
        var rows = spectra.listCharts(actor.organizationId(), filter.path("query").asText(""), optionalId(filter, "category_id"), filter.path("status").asText(null), page, size);
        return new Page(rows.stream().map(row -> ref(type, row.id())).toList(), rows.size() == size);
    }
    public BusinessRecordView read(ObjectNode request) {
        var actor = ActorContext.required(); var id = id(request); var type = request.path("type").asText();
        if ("SPECTRUM_ANALYSIS".equals(type)) {
            var row = spectra.findAnalysisForUser(actor.organizationId(), actor.userId(), id).orElseThrow(BusinessViews::missing);
            var body = facts(json, row, "status", "question", "resultJson", "warningJson", "model", "createdAt", "completedAt");
            body.put("source_status", "SECONDARY_AI_ANALYSIS");
            var result = view(type, id, null, "历史谱图分析", body, "/spectrum/chat?sessionId=" + row.sessionId(), List.of()); pinned(request, result); return result;
        }
        var row = spectra.findChart(actor.organizationId(), id).orElseThrow(BusinessViews::missing);
        var body = facts(json, row, "title", "categoryId", "originalName", "sampleName", "batchNo", "testConditions", "metadataJson", "pageCount", "status", "fileObjectId", "sha256");
        body.put("measurement_data", "原文件及页面；未提供可直接计算峰面积的原始数值曲线");
        body.put("original_path", "/api/v1/spc/charts/" + id + "/content");
        body.put("file_read_hint", "business_read设置include_file=true可把完整原文件交给原生文件读取；按原生PDF页码选择页面");
        var result = view(type, id, null, row.title(), body, "/spectrum/view?chartId=" + id, List.of()); pinned(request, result); return result;
    }
    @Override public FileContent file(ObjectNode reference) {
        if (!"SPECTRUM".equals(reference.path("type").asText())) return BusinessReadPort.super.file(reference);
        var expected = read(reference);
        try (var file = files.openChart(id(reference)).file()) {
            if (file.size() > 8 * 1024 * 1024) throw new ApiException(ApiErrorCode.BAD_REQUEST, "谱图原文件超过8MB，请通过业务页面下载后作为原生附件读取");
            var bytes = file.stream().readNBytes(8 * 1024 * 1024 + 1);
            String actualHash = hash(bytes);
            if (bytes.length != file.size() || !actualHash.equals(expected.facts().path("sha256").asText()) || !actualHash.equals(file.sha256()))
                throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "谱图原文件与登记哈希不一致");
            return new FileContent(file.originalName(), file.contentType(), bytes, actualHash);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw new ApiException(ApiErrorCode.FILE_NOT_READY, "谱图原文件暂不可读取"); }
    }
}
