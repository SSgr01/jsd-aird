package com.jsd.aird.ai.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import com.jsd.aird.kb.api.KnowledgeSearchFacade;
import org.springframework.stereotype.Service;

/**
 * Keeps retrieval metadata intact while shrinking untrusted source text to a bounded context window.
 * It is deterministic and therefore remains available when a second model call is unavailable.
 */
@Service
public class ContextCompressionService {

    private static final Pattern BUSINESS_RECORD_PATTERN = Pattern.compile("(?:^|[；;])\\s*记录\\s*[=:：]\\s*([^；;\\s]+)");
    private static final Pattern EXPERIMENT_NUMBER_PATTERN = Pattern.compile("(?:^|[；;])\\s*实验编号\\s*[=:：]\\s*([^；;\\s]+)");

    public Context compress(List<KnowledgeSearchFacade.SearchHit> knowledge,
                            List<DataSourceFileSearchFacade.SourceFileHit> dataFiles, int maxChars) {
        return compress(knowledge, dataFiles, maxChars, List.of());
    }

    public Context compress(List<KnowledgeSearchFacade.SearchHit> knowledge,
                            List<DataSourceFileSearchFacade.SourceFileHit> dataFiles, int maxChars,
                            List<com.jsd.aird.kb.api.ResearchKnowledgeFacade.Candidate> parents) {
        var chunks = new ArrayList<String>();
        var includedEvidenceRefs = new ArrayList<String>();
        var used = 0;
        var budget = Math.max(0, maxChars);
        var safeKnowledge = knowledge == null ? List.<KnowledgeSearchFacade.SearchHit>of() : knowledge;
        var safeDataFiles = dataFiles == null ? List.<DataSourceFileSearchFacade.SourceFileHit>of() : dataFiles;
        // Keep a stable half-budget for knowledge evidence whenever both
        // sources are present. A structured-data query should not silently
        // shrink the knowledge-base context further in a mixed-scope answer.
        var knowledgeBudget = safeDataFiles.isEmpty() ? budget : budget / 2;
        var parentParts = new LinkedHashMap<String,String>();
        int parentReserved = 0;
        for (int i=0; i<parents.size(); i++) {
            var parent=parents.get(i);
            var label="K"+(safeKnowledge.size()+i+1);
            var part="[source=knowledge,relation=PARENT_CONTEXT,file="+parent.source().title()+"] [evidenceRef="+label+"] "+parent.text();
            // A parent is an independently hashed exact span. Never truncate or normalize its body here.
            if (parentReserved+part.length()+2 > Math.min(6000,knowledgeBudget/3)) continue;
            parentParts.put(label,part);parentReserved+=part.length()+2;
        }
        // Preserve the retrieval ranking when allocating the context budget.
        // Grouping by page used to move low-ranked long chunks ahead of more
        // relevant hits, cutting off the very passage that answered the question.
        for (var index = 0; index < safeKnowledge.size(); index++) {
            var hit = safeKnowledge.get(index);
            var remaining = Math.max(0, knowledgeBudget - parentReserved - used);
            var text = normalized(hit.content());
            var image = imageMarkdown(hit);
            if (text.isBlank() && image.isBlank()) continue;
            var evidenceRef = "K" + (index + 1);
            var evidenceText = "[evidenceRef=" + evidenceRef + "] " + text
                    + (image.isBlank() ? "" : " " + image);
            var prefix = "[source=knowledge,file=" + hit.title()
                    + ",page=" + hit.pageNo() + ",section=" + hit.section() + "] ";
            var separatorLength = chunks.isEmpty() ? 0 : 2;
            var contentBudget = remaining - prefix.length() - separatorLength;
            var firstEvidenceRef = "[evidenceRef=" + evidenceRef + "]";
            if (contentBudget < firstEvidenceRef.length()) break;
            var excerpt = evidenceText.length() <= contentBudget ? evidenceText
                    : evidenceText.substring(0, Math.max(0, contentBudget - 1)) + "…";
            chunks.add(prefix + excerpt);
            if (excerpt.contains(firstEvidenceRef)) includedEvidenceRefs.add(evidenceRef);
            used += prefix.length() + excerpt.length() + separatorLength;
        }
        for (var entry : parentParts.entrySet()) {
            var separator=chunks.isEmpty()?0:2;
            if(used+entry.getValue().length()+separator > knowledgeBudget) continue;
            chunks.add(entry.getValue());includedEvidenceRefs.add(entry.getKey());used+=entry.getValue().length()+separator;
        }
        for (var index = 0; index < safeDataFiles.size(); index++) {
            var hit = safeDataFiles.get(index);
            var remaining = Math.max(0, budget - used);
            var text = normalized(hit.content());
            var evidenceRef = "D" + (index + 1);
            var sourceContent = hit.content() == null ? "" : hit.content();
            var evidenceText = "[evidenceRef=" + evidenceRef + "] "
                    + "；来源状态=" + sourceStatusLabel(hit.sourceStatus())
                    + "；字段=" + cleanValue(hit.sourceLabel() == null ? hit.fieldName() : hit.sourceLabel())
                    + "；值=" + cleanValue(hit.fieldValue())
                    + "；单位=" + cleanValue(hit.unit())
                    + "；条件=" + cleanValue(hit.declaredCondition())
                    + "；证据关系=" + contextRelationLabel(hit.contextRelation())
                    + "；原始内容=" + text;
            var sourceLabel = "EXPERIMENT".equalsIgnoreCase(hit.sourceOwner()) ? "实验本记录" : "数据中心记录";
            var experimentNumber = experimentNumberLabel(sourceContent);
            var prefix = "[来源=" + sourceLabel + "; 文件=" + cleanValue(hit.originalName())
                    + (experimentNumber.isBlank() ? "" : "; 实验编号=" + experimentNumber)
                    + "; 业务记录=" + businessRecordLabel(hit) + "; 工作表=" + cleanValue(hit.sheetName()) + "] ";
            var separatorLength = chunks.isEmpty() ? 0 : 2;
            var contentBudget = remaining - prefix.length() - separatorLength;
            if (contentBudget < 80) break;
            var excerpt = evidenceText.length() <= contentBudget ? evidenceText
                    : evidenceText.substring(0, Math.max(0, contentBudget - 1)) + "…";
            chunks.add(prefix + excerpt);
            if (excerpt.contains("[evidenceRef=" + evidenceRef + "]")) includedEvidenceRefs.add(evidenceRef);
            used += prefix.length() + excerpt.length() + separatorLength;
        }
        var knowledgeCount = (int) includedEvidenceRefs.stream().filter(ref -> ref.startsWith("K")).count();
        var dataFileCount = includedEvidenceRefs.size() - knowledgeCount;
        return new Context(String.join("\n\n", chunks), used, knowledgeCount, dataFileCount,
                List.copyOf(includedEvidenceRefs));
    }

    private String normalized(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private String cleanValue(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").strip();
    }

    private String businessRecordLabel(DataSourceFileSearchFacade.SourceFileHit hit) {
        var content = hit.content() == null ? "" : hit.content();
        Matcher matcher = BUSINESS_RECORD_PATTERN.matcher(content);
        if (matcher.find()) return matcher.group(1).strip();
        var recordKey = hit.recordKey();
        if (recordKey == null || recordKey.isBlank() || recordKey.matches("(?i)^IMPORT[:_-].*")) {
            return "未提供";
        }
        return cleanValue(recordKey);
    }

    private String experimentNumberLabel(String content) {
        Matcher matcher = EXPERIMENT_NUMBER_PATTERN.matcher(content == null ? "" : content);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private String sourceStatusLabel(String value) {
        if (value == null) return "状态未提供";
        return switch (value.strip().toUpperCase(java.util.Locale.ROOT)) {
            case "DRAFT" -> "草稿";
            case "CONFIRMED", "COMPLETED" -> "已确认";
            case "UNCONFIRMED" -> "未正式确认";
            default -> "状态未知";
        };
    }

    private String contextRelationLabel(String value) {
        if (value == null) return "未说明";
        return switch (value.strip().toUpperCase(java.util.Locale.ROOT)) {
            case "TARGET" -> "本次命中字段";
            case "SAME_RECORD" -> "同一业务记录上下文";
            case "SHARED_SHEET" -> "同一工作表共享条件";
            case "COMPARISON_CANDIDATE" -> "可比记录候选";
            default -> "未说明";
        };
    }

    private String imageMarkdown(KnowledgeSearchFacade.SearchHit hit) {
        var anchors = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        if (hit.anchor() != null && !hit.anchor().isMissingNode() && !hit.anchor().isNull()) anchors.add(hit.anchor());
        if (hit.anchors() != null) anchors.addAll(hit.anchors());
        for (var anchor : anchors) {
            var assetId = anchor.path("assetFileId").asText("");
            if (assetId.isBlank()) continue;
            var caption = anchor.path("caption").asText("").replace("]", "").replace("\n", " ").strip();
            return "![" + caption + "](/api/v1/knowledge/assets/" + assetId + "/content)";
        }
        return "";
    }

    public record Context(String text, int characterCount, int knowledgeCount, int dataFileCount,
                          List<String> evidenceRefs) {
    }
}
