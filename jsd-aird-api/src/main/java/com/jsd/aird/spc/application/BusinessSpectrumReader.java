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
    private final SpectrumPageRenderer pages;
    public BusinessSpectrumReader(SpectrumRepository spectra, SpectrumService files, ObjectMapper json, SpectrumPageRenderer pages) { this.spectra = spectra; this.files = files; this.json = json; this.pages = pages; }
    public Set<String> resourceTypes() { return Set.of("SPECTRUM"); }
    public Set<String> filters(String type) { return "SPECTRUM".equals(type) ? Set.of("query", "category_id", "status") : Set.of(); }
    public Page references(String type, ObjectNode filter, int page, int size) {
        if (!"SPECTRUM".equals(type)) throw new ApiException(ApiErrorCode.BAD_REQUEST, "不支持此业务类型");
        var actor = ActorContext.required();
        var rows = spectra.listCharts(actor.organizationId(), filter.path("query").asText(""), optionalId(filter, "category_id"), filter.path("status").asText(null), page, size);
        return new Page(rows.stream().map(row -> ref(type, row.id())).toList(), rows.size() == size);
    }
    public BusinessRecordView read(ObjectNode request) {
        var actor = ActorContext.required(); var id = id(request); var type = request.path("type").asText();
        var row = spectra.findChart(actor.organizationId(), id).orElseThrow(BusinessViews::missing);
        var body = facts(json, row, "title", "categoryId", "originalName", "sampleName", "batchNo", "testConditions", "metadataJson", "pageCount", "status", "fileObjectId", "sha256");
        try {
            var snapshot = json.readTree(row.metadataJson() == null ? "{}" : row.metadataJson()).path("testMethodSnapshot");
            if (snapshot.isObject()) {
                body.set("testMethodSnapshot", snapshot);
                body.set("conditionWarnings", SpectrumTestMethods.warnings(snapshot));
            }
        } catch (java.io.IOException ignored) { /* Legacy metadata remains available verbatim. */ }
        body.put("conditionAnalysisHint", "先读取当次测试条件快照，再结合原始页。登记值、报告明示值、用户补充值分别标明来源；冲突须并列说明。ATR/透射、浓度/光程、GPC校准、粒径介质/分布口径、GC温程、拉伸截面积/速度的差异按问题限制比较；缺项不是相同，不猜常用值。拉伸缺实测宽度或厚度时，只给公式和缺项，不代入假设尺寸举出MPa数值；标准版本或原文未提供，不引用记忆中的标准尺寸或判定合规。预设带入是登记条件，不证明仪器实际执行。图片没有原始数值时不自行积分或建立校准曲线。");
        body.put("measurement_data", "原文件及页面；未提供可直接计算峰面积的原始数值曲线");
        body.put("original_path", "/api/v1/spc/charts/" + id + "/content");
        body.put("file_read_hint", "business_read设置include_file=true可把完整原文件交给原生文件读取；按原生PDF页码选择页面");
        body.put("page_read_hint", "按页看图：business_read(ref=本记录ref,section=page,page=原始页码,include_file=true)，再用read_image读取business_file.path。只有图片实际进入视觉模型才算看图。");
        body.put("categoryHint", files.requireCategory(actor.organizationId(), row.categoryId()).analysisHint());
        var result = view(type, id, null, row.title(), body, "/spectrum/view?chartId=" + id, List.of()); pinned(request, result); return result;
    }
    @Override public BusinessRecordView imagePage(ObjectNode reference, int page) {
        var record = read(reference);
        if (!"SPECTRUM".equals(reference.path("type").asText()) || !"READY".equals(record.facts().path("status").asText())
                || page < 1 || page > record.facts().path("pageCount").asInt())
            throw new ApiException(ApiErrorCode.BAD_REQUEST, "图谱页面不可用或页码超出范围");
        var facts = record.facts().deepCopy();
        facts.put("originalPage", page).put("visualReading", "本条仅为页面来源与样品信息；须读取对应图片后再作图像判断。");
        return new BusinessRecordView(record.ref(), record.title() + " · 第 " + page + " 页", facts, record.relations(), record.path());
    }
    @Override public FileContent imagePageFile(ObjectNode reference, int page) {
        var record = imagePage(reference, page);
        return pages.render(id(reference), record.facts().path("sha256").asText(), page);
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
