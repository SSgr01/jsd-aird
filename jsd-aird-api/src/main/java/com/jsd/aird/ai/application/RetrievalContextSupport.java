package com.jsd.aird.ai.application;

import com.jsd.aird.data.api.DataSourceFileSearchFacade;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/** Pure retrieval context rules shared by fixed QA and scoped research retrieval. */
final class RetrievalContextSupport {
    private RetrievalContextSupport() { }
    static List<DataSourceFileSearchFacade.SourceFileHit> answerDataHits(RagRetrievalService.Retrieval retrieval) {
        if (retrieval == null) return List.of();
        var result = new ArrayList<DataSourceFileSearchFacade.SourceFileHit>();
        var seen = new HashSet<UUID>();
        for (var hit : retrieval.dataHits()) {
            if (hit.hitId() == null || seen.add(hit.hitId())) result.add(hit);
        }
        for (var context : retrieval.dataDetail().recordContexts()) {
            for (var hit : context.contextHits()) {
                if (hit.hitId() == null || seen.add(hit.hitId())) result.add(hit);
            }
        }
        return List.copyOf(result);
    }

    static String formulaConsistencyContext(String question,
                                            List<DataSourceFileSearchFacade.SourceFileHit> hits) {
        if (question == null || !question.matches("(?s).*(配方|组分|组成|合计|总计|添加量).*")) return "";
        if (hits == null || hits.isEmpty()) return "";
        var groups = new LinkedHashMap<String, List<DataSourceFileSearchFacade.SourceFileHit>>();
        for (var hit : hits) {
            // Imported totals may be mapped as OTHER/DYNAMIC_VALUE while the
            // individual ingredients are correctly classified as FORMULA.
            // Keep named total fields for comparison, but never sum arbitrary
            // numeric observations as formula components.
            if (hit == null || (!"FORMULA".equalsIgnoreCase(hit.semanticDomain())
                    && !isFormulaTotal(hit))) continue;
            var group = String.join("|", safeText(hit.fileObjectId() == null ? null : hit.fileObjectId().toString()),
                    safeText(hit.importJobId() == null ? null : hit.importJobId().toString()),
                    businessRecordKey(hit), safeText(hit.sheetName()));
            groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(hit);
        }
        var checks = new ArrayList<String>();
        for (var group : groups.values()) {
            var totals = group.stream().filter(RetrievalContextSupport::isFormulaTotal)
                    .filter(hit -> numericValue(hit.fieldValue()) != null).toList();
            var components = group.stream().filter(hit -> "FORMULA".equalsIgnoreCase(hit.semanticDomain()))
                    .filter(hit -> !isFormulaTotal(hit))
                    .filter(hit -> numericValue(hit.fieldValue()) != null)
                    .collect(java.util.stream.Collectors.toMap(
                            hit -> String.join("|", safeText(hit.sourceLabel()), safeText(hit.fieldName()),
                                    safeText(hit.fieldValue()), safeText(hit.unit())),
                            hit -> hit, (left, right) -> left, LinkedHashMap::new)).values().stream().toList();
            if (totals.size() != 1 || components.size() < 2) continue;
            var total = totals.getFirst();
            var unit = safeText(total.unit()).strip();
            if (components.stream().anyMatch(hit -> !safeText(hit.unit()).strip().equals(unit))) continue;
            var sum = components.stream().map(hit -> numericValue(hit.fieldValue()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            var sourceTotal = numericValue(total.fieldValue());
            if (sourceTotal == null) continue;
            var difference = sourceTotal.subtract(sum);
            if (difference.compareTo(BigDecimal.ZERO) == 0) continue;
            var representative = total;
            var record = displayBusinessRecord(representative);
            var componentValues = components.stream().map(hit -> {
                        var label = safeText(hit.fieldName()).isBlank()
                                ? safeText(hit.sourceLabel()) : safeText(hit.fieldName());
                        var value = safeText(hit.fieldValue());
                        var componentUnit = safeText(hit.unit()).strip();
                        return label + "=" + value + (componentUnit.isBlank() ? "" : " " + componentUnit);
                    })
                    .collect(java.util.stream.Collectors.joining("；"));
            checks.add("文件《" + safeText(representative.originalName()) + "》业务记录“" + record
                    + "”：检索到的组分明细=" + componentValues
                    + "；组分之和=" + decimalText(sum) + (unit.isBlank() ? "" : " " + unit)
                    + "，来源合计=" + decimalText(sourceTotal) + (unit.isBlank() ? "" : " " + unit)
                    + "，差额（来源合计－组分之和）=" + signedDecimalText(difference));
        }
        return String.join("；", checks);
    }

    static boolean isFormulaTotal(DataSourceFileSearchFacade.SourceFileHit hit) {
        var label = (safeText(hit.sourceLabel()) + " " + safeText(hit.fieldName()) + " "
                + safeText(hit.semanticField())).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        return label.matches(".*(合计|总计|小计|总和|total|subtotal|sum).*" );
    }

    static BigDecimal numericValue(String raw) {
        if (raw == null) return null;
        var value = raw.strip().replace(",", "");
        if (!value.matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static String displayBusinessRecord(DataSourceFileSearchFacade.SourceFileHit hit) {
        var matcher = Pattern.compile("(?i)记录\\s*[：:=]\\s*([^；;]+)[；;]\\s*字段").matcher(safeText(hit.content()));
        if (matcher.find()) return matcher.group(1).strip();
        var key = safeText(hit.recordKey()).strip();
        return key.isBlank() || key.matches("(?i)^IMPORT[:_-].*") ? "未提供" : key;
    }

    static String businessRecordKey(DataSourceFileSearchFacade.SourceFileHit hit) {
        var matcher = Pattern.compile("(?i)记录\\s*[：:=]\\s*([^；;]+)[；;]\\s*字段").matcher(safeText(hit.content()));
        return matcher.find() ? matcher.group(1).strip() : safeText(hit.recordKey());
    }

    static String decimalText(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    static String signedDecimalText(BigDecimal value) {
        var text = decimalText(value);
        return value.signum() > 0 ? "+" + text : text;
    }

    static String dataLookupContext(DataSourceFileSearchFacade.DataDetailResult detail) {
        return dataLookupContext(detail, "EMPTY");
    }

    static String dataLookupContext(DataSourceFileSearchFacade.DataDetailResult detail, boolean dataChannelSkipped) {
        return dataLookupContext(detail, dataChannelSkipped ? "SKIPPED" : "SUCCEEDED");
    }

    static String dataLookupContext(DataSourceFileSearchFacade.DataDetailResult detail, String dataChannelStatus) {
        if (detail == null || detail.mode() == DataSourceFileSearchFacade.DataQueryMode.NONE) {
            var searchSummary = switch (dataChannelStatus) {
                case "SUCCEEDED" -> "常规结构化检索已找到相关证据，见上方上下文。";
                case "EMPTY" -> "常规结构化检索在当前授权范围内未返回匹配内容。";
                case "SKIPPED" -> "本轮选择的检索范围不包含结构化数据来源，未执行这部分检索。";
                case "FAILED" -> "常规结构化检索未能完成。";
                case "PARTIAL" -> "常规结构化检索部分失败，只能依据已返回的证据回答。";
                default -> "本轮结构化检索结果未能确定。";
            };
            return "本轮未执行精确的记录/字段定位；" + searchSummary;
        }
        var exactResultEmpty = detail.hits().isEmpty();
        var state = "SKIPPED".equals(dataChannelStatus)
                ? "本轮没有检索结构化数据来源，因为用户选择的范围不包含数据中心或实验记录。"
                : "FAILED".equals(dataChannelStatus)
                ? "结构化数据检索未能完成。"
                : "PARTIAL".equals(dataChannelStatus)
                ? "结构化数据检索部分失败；只可依据已提供的命中回答，不能声称结果完整。"
                : "SUCCEEDED".equals(dataChannelStatus) && exactResultEmpty
                ? "精确的记录/字段定位未返回目标值，但一般结构化检索已找到相关证据；请依据上方命中内容回答。"
                : "EMPTY".equals(dataChannelStatus) && exactResultEmpty
                ? "精确定位和一般结构化检索在当前授权范围内均未找到匹配文件或记录。"
                : switch (detail.resolution()) {
            case RESOLVED -> detail.hits().isEmpty()
                    ? "查询已执行，但没有找到匹配的非空记录值。"
                    : "查询已执行并找到匹配记录，命中数据见证据上下文。";
            case FILE_REQUIRED -> "精确定位缺少唯一记录或文件线索；一般结构化检索结果仍需以已提供的证据为准。";
            case AMBIGUOUS -> "查询命中多个同等匹配的数据文件，需要用户选择文件。";
            case NOT_FOUND -> "当前授权数据范围内未找到匹配文件或记录。";
            case NOT_REQUESTED -> "本轮没有执行精确的记录/字段查询。";
        };
        var result = new StringBuilder("查询类型=").append(detail.mode())
                .append("；结果=").append(state)
                .append("；命中记录值数=").append(detail.hits().size());
        if (StringUtils.hasText(detail.originalName())) {
            result.append("；来源文件=").append(promptText(detail.originalName(), ""));
        }
        var boundary = dataEvidenceBoundary(detail);
        if (boundary.observationOnly()) result.append("；回答限制=").append(boundary.description());
        if (detail.truncated()) result.append("；结果已截断，只能概括已提供的命中");
        return result.toString();
    }

    static String normalizedAnswerStatus(String value) {
        if (!StringUtils.hasText(value)) return "NOT_FOUND";
        var normalized = value.strip().toUpperCase(Locale.ROOT);
        return Set.of("ANSWERED", "PARTIAL", "NOT_FOUND").contains(normalized) ? normalized : "NOT_FOUND";
    }

    static String analysisGoalCode(DataSourceFileSearchFacade.DataDetailResult detail) {
        return detail == null ? "VALUE" : detail.analysisGoal();
    }

    static int contextCandidateCount(DataSourceFileSearchFacade.DataDetailResult detail) {
        if (detail == null || detail.recordContexts() == null) return 0;
        return detail.recordContexts().stream().mapToInt(context -> context.contextHits().size()).sum();
    }

    static int contextSelectedCount(DataSourceFileSearchFacade.DataDetailResult detail) {
        return contextCandidateCount(detail);
    }

    static int contextTruncatedCount(DataSourceFileSearchFacade.DataDetailResult detail) {
        if (detail == null || detail.recordContexts() == null) return 0;
        return (int) detail.recordContexts().stream().filter(DataSourceFileSearchFacade.RecordContext::contextTruncated).count();
    }

    static int comparableRecordCount(DataSourceFileSearchFacade.DataDetailResult detail) {
        if (detail == null || detail.recordContexts() == null) return 0;
        return (int) detail.recordContexts().stream().filter(DataSourceFileSearchFacade.RecordContext::comparable).count();
    }

    static int conditionConflictCount(DataSourceFileSearchFacade.DataDetailResult detail) {
        if (detail == null) return 0;
        var groups = new LinkedHashMap<String, Set<String>>();
        for (var hit : detail.hits()) groups.computeIfAbsent(fieldGroupKey(hit), ignored -> new java.util.LinkedHashSet<>())
                .add(comparisonSignature(hit));
        return (int) groups.values().stream().filter(values -> values.size() > 1).count();
    }

    static String promptText(String value, String fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        return value.replaceAll("[\\r\\n\\t]+", " ").strip();
    }

    static String sourceLabel(DataSourceFileSearchFacade.SourceFileHit hit) {
        return hit == null ? "" : StringUtils.hasText(hit.sourceLabel()) ? hit.sourceLabel() : hit.fieldName();
    }

    static DataEvidenceBoundary dataEvidenceBoundary(DataSourceFileSearchFacade.DataDetailResult detail) {
        var hits = detail == null || detail.hits() == null ? List.<DataSourceFileSearchFacade.SourceFileHit>of()
                : detail.hits();
        var contextHits = detail == null ? List.<DataSourceFileSearchFacade.SourceFileHit>of()
                : detail.recordContexts().stream().flatMap(context -> context.contextHits().stream()).toList();
        var allEvidenceHits = new ArrayList<DataSourceFileSearchFacade.SourceFileHit>(hits);
        allEvidenceHits.addAll(contextHits);
        var aliases = recordAliases(hits);
        var draft = allEvidenceHits.stream().anyMatch(hit -> "DRAFT".equalsIgnoreCase(safeText(hit.sourceStatus())));
        var unconfirmed = allEvidenceHits.stream().anyMatch(RetrievalContextSupport::observationOnlySource);
        var confirmed = allEvidenceHits.stream().anyMatch(hit -> {
            var status = safeText(hit.sourceStatus()).strip();
            return "CONFIRMED".equalsIgnoreCase(status) || "COMPLETED".equalsIgnoreCase(status);
        });
        var grouped = new LinkedHashMap<String, Set<String>>();
        for (var hit : hits) {
            grouped.computeIfAbsent(fieldGroupKey(hit), ignored -> new java.util.LinkedHashSet<>())
                    .add(recordBoundaryKey(hit));
        }
        var signatures = new LinkedHashMap<String, Set<String>>();
        for (var hit : hits) signatures.computeIfAbsent(fieldGroupKey(hit), ignored -> new java.util.LinkedHashSet<>())
                .add(comparisonSignature(hit));
        var comparable = (int) grouped.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .filter(entry -> signatures.getOrDefault(entry.getKey(), Set.of()).size() == 1)
                .count();
        var observationOnly = draft || unconfirmed;
        var description = observationOnly && confirmed
                ? "来源状态混合：既有已确认记录，也有草稿或未正式确认来源。逐条注明状态；整体不作达标、优劣、稳定性或推荐判断。"
                : draft
                ? "包含草稿实验；只允许陈述和比较观察值，不用于达标、优劣、稳定性、原因或推荐判断。"
                : unconfirmed
                ? "包含未正式确认来源；只允许陈述和比较观察值，不用于最终研发判断。"
                : "来源状态已确认；仍需在单位、测试方法和条件一致时才可比较，不自动推断因果。";
        return new DataEvidenceBoundary(observationOnly, aliases.size(), comparable, description);
    }

    static boolean observationOnlySource(DataSourceFileSearchFacade.SourceFileHit hit) {
        var status = safeText(hit.sourceStatus()).strip().toUpperCase(Locale.ROOT);
        return !("CONFIRMED".equals(status) || "COMPLETED".equals(status));
    }

    static Map<String, String> recordAliases(List<DataSourceFileSearchFacade.SourceFileHit> hits) {
        var result = new LinkedHashMap<String, String>();
        if (hits == null) return result;
        for (var hit : hits) {
            var key = recordBoundaryKey(hit);
            result.computeIfAbsent(key, ignored -> "观察" + (result.size() + 1));
        }
        return result;
    }

    static String recordBoundaryKey(DataSourceFileSearchFacade.SourceFileHit hit) {
        if (hit == null) return "UNKNOWN";
        return safeText(hit.importJobId() == null ? null : hit.importJobId().toString()) + "|"
                + safeText(hit.recordKey()) + "|" + safeText(hit.sheetName());
    }

    static String fieldGroupKey(DataSourceFileSearchFacade.SourceFileHit hit) {
        if (hit == null) return "UNKNOWN";
        var semantic = normalizeLookup(hit.semanticDomain()) + "/" + normalizeLookup(hit.semanticField());
        if (!semantic.equals("/")) return semantic;
        var name = normalizeLookup(hit.fieldName());
        return name.isBlank() ? normalizeLookup(hit.fieldCode()) : name;
    }

    static String comparisonSignature(DataSourceFileSearchFacade.SourceFileHit hit) {
        if (hit == null) return "";
        return fieldGroupKey(hit) + "|label=" + normalizeLookup(sourceLabel(hit))
                + "|declared=" + normalizeLookup(hit.declaredCondition())
                + "|unit=" + normalizeLookup(StringUtils.hasText(hit.standardUnit()) ? hit.standardUnit() : hit.unit());
    }

    static String sourceStatusLabel(DataSourceFileSearchFacade.SourceFileHit hit) {
        var status = safeText(hit.sourceStatus()).strip().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "CONFIRMED", "COMPLETED" -> "已确认";
            case "UNCONFIRMED" -> "未正式确认";
            default -> "状态未知，按未确认处理";
        };
    }

    static String safeDisplayText(String value, String fallback) {
        if (!StringUtils.hasText(value)) return fallback;
        return escapeMarkdown(value.replaceAll("[\\p{Cntrl}]+", " ").strip());
    }

    static FieldAmbiguity detectFieldAmbiguity(String question,
                                                List<DataSourceFileSearchFacade.SourceFileHit> hits) {
        var normalizedQuestion = normalizeLookup(question);
        if (normalizedQuestion.isBlank() || hits == null || hits.isEmpty()) return null;
        var grouped = new LinkedHashMap<String, LinkedHashMap<String, FieldCandidate>>();
        for (var hit : hits) {
            if (!StringUtils.hasText(hit.fieldName()) || !StringUtils.hasText(hit.fieldCode())) continue;
            var normalizedName = normalizeLookup(hit.fieldName());
            var normalizedCode = normalizeLookup(hit.fieldCode());
            if (normalizedName.length() < 2
                    || !(normalizedQuestion.contains(normalizedName) || normalizedQuestion.contains(normalizedCode))) {
                continue;
            }
            var candidate = new FieldCandidate(hit.fieldCode(), hit.valueType(), hit.unit());
            var signature = String.join("|", candidate.code(), safeText(candidate.valueType()), safeText(candidate.unit()));
            grouped.computeIfAbsent(hit.fieldName(), ignored -> new LinkedHashMap<>()).putIfAbsent(signature, candidate);
        }
        return grouped.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new FieldAmbiguity(entry.getKey(), entry.getValue().values().stream()
                        .sorted(java.util.Comparator.comparing(FieldCandidate::code)).toList()))
                .findFirst().orElse(null);
    }

    static String normalizeLookup(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9\\u4E00-\\u9FFF]", "");
    }

    static String markdownCell(String value, String fallback) {
        var normalized = StringUtils.hasText(value) ? value.strip() : fallback;
        normalized = normalized.replace('|', '｜').replaceAll("[\\r\\n\\t]+", " / ");
        normalized = escapeMarkdown(normalized);
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 239) + "…";
    }

    static String escapeMarkdown(String value) {
        return value.replace("&", "&amp;").replace("*", "&#42;").replace("_", "&#95;").replace("`", "&#96;");
    }

    static String safeText(String value) {
        return value == null ? "" : value;
    }

    record FieldCandidate(String code, String valueType, String unit) { }
    record FieldAmbiguity(String fieldName, List<FieldCandidate> candidates) { }
    record DataEvidenceBoundary(boolean observationOnly, int recordCount, int comparableFieldCount,
                                String description) { }
}
