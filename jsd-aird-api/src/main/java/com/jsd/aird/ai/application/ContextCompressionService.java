package com.jsd.aird.ai.application;

import java.util.ArrayList;
import java.util.HashMap;
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

    /** Backward-compatible entry point; context allocation is now source-neutral. */
    @Deprecated(forRemoval = false)
    public Context compress(List<KnowledgeSearchFacade.SearchHit> knowledge,
                            List<DataSourceFileSearchFacade.SourceFileHit> dataFiles,
                            int maxChars, boolean ignoredPrioritizeStructuredData) {
        return compress(knowledge, dataFiles, maxChars);
    }

    public Context compress(List<KnowledgeSearchFacade.SearchHit> knowledge,
                            List<DataSourceFileSearchFacade.SourceFileHit> dataFiles, int maxChars) {
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
        var knowledgeRefs = new HashMap<java.util.UUID, String>();
        for (var index = 0; index < safeKnowledge.size(); index++) {
            knowledgeRefs.put(safeKnowledge.get(index).chunkId(), "K" + (index + 1));
        }
        var groupedKnowledge = new LinkedHashMap<String, List<KnowledgeSearchFacade.SearchHit>>();
        for (var hit : safeKnowledge) {
            var key = String.join("|", String.valueOf(hit.documentId()), String.valueOf(hit.versionId()),
                    String.valueOf(hit.pageNo()), String.valueOf(hit.section()));
            groupedKnowledge.computeIfAbsent(key, ignored -> new ArrayList<>()).add(hit);
        }
        for (var group : groupedKnowledge.values()) {
            var remaining = Math.max(0, knowledgeBudget - used);
            var groupParts = new ArrayList<String>();
            var groupRefs = new ArrayList<String>();
            for (var hit : group) {
                var text = normalized(hit.content());
                var image = imageMarkdown(hit);
                if (text.isBlank() && image.isBlank()) continue;
                var evidenceRef = knowledgeRefs.get(hit.chunkId());
                groupRefs.add(evidenceRef);
                groupParts.add("[evidenceRef=" + evidenceRef + "] " + text
                        + (image.isBlank() ? "" : " " + image));
            }
            if (groupParts.isEmpty()) continue;
            var groupText = String.join(" ", groupParts);
            var first = group.get(0);
            var prefix = "[source=knowledge,file=" + first.title()
                    + ",page=" + first.pageNo() + ",section=" + first.section() + "] ";
            var separatorLength = chunks.isEmpty() ? 0 : 2;
            var contentBudget = remaining - prefix.length() - separatorLength;
            var firstEvidenceRef = "[evidenceRef=" + groupRefs.getFirst() + "]";
            if (contentBudget < firstEvidenceRef.length()) break;
            var excerpt = groupText.length() <= contentBudget ? groupText
                    : groupText.substring(0, Math.max(0, contentBudget - 1)) + "…";
            chunks.add(prefix + excerpt);
            for (var evidenceRef : groupRefs) {
                if (excerpt.contains("[evidenceRef=" + evidenceRef + "]")) includedEvidenceRefs.add(evidenceRef);
            }
            used += prefix.length() + excerpt.length() + separatorLength;
        }
        for (var index = 0; index < safeDataFiles.size(); index++) {
            var hit = safeDataFiles.get(index);
            var remaining = Math.max(0, budget - used);
            var text = hit.content() == null ? "" : hit.content().replaceAll("[\\r\\n\\t]+", " ").strip();
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
        return value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").strip();
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
