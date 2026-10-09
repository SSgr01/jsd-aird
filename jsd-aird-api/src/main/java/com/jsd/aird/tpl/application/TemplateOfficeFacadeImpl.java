package com.jsd.aird.tpl.application;

import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.tpl.api.TemplateOfficeFacade;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.application.port.WordOoxmlPatcher;
import com.jsd.aird.tpl.application.port.WorkbookSnapshotStructureParser;
import com.jsd.aird.tpl.domain.TemplateFormat;

import org.springframework.stereotype.Service;

/** Adapts template-center parsers to the stable cross-module API. */
@Service
public class TemplateOfficeFacadeImpl implements TemplateOfficeFacade {

    private final List<OfficeStructureParser> officeParsers;
    private final WorkbookSnapshotStructureParser snapshotParser;
    private final RuleBasedRecognitionEngine recognitionEngine;
    private final WordOoxmlPatcher wordPatcher;
    private final TemplateImportService importService;

    public TemplateOfficeFacadeImpl(
            List<OfficeStructureParser> officeParsers,
            WorkbookSnapshotStructureParser snapshotParser,
            RuleBasedRecognitionEngine recognitionEngine,
            WordOoxmlPatcher wordPatcher,
            TemplateImportService importService
    ) {
        this.officeParsers = List.copyOf(officeParsers);
        this.snapshotParser = snapshotParser;
        this.recognitionEngine = recognitionEngine;
        this.wordPatcher = wordPatcher;
        this.importService = importService;
    }

    @Override
    public ParseResult parseOffice(String format, InputStream input) {
        var templateFormat = TemplateFormat.valueOf(format.toUpperCase(java.util.Locale.ROOT));
        var parser = officeParsers.stream()
                .filter(item -> item.format() == templateFormat)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No parser for " + templateFormat));
        return convert(parser.parse(input));
    }

    @Override
    public ParseResult parseWorkbookSnapshot(InputStream input) {
        return convert(snapshotParser.parse(input));
    }

    @Override
    public RecognitionBatch recognize(String format, String sourceFileName, JsonNode structureSummary) {
        var batch = recognitionEngine.recognize(
                TemplateFormat.valueOf(format.toUpperCase(java.util.Locale.ROOT)),
                sourceFileName,
                structureSummary);
        return new RecognitionBatch(batch.suggestions().stream()
                .map(item -> new Suggestion(item.suggestionType(), item.confidence(), item.payload(), item.evidence()))
                .toList());
    }

    @Override
    public WorkbookRecognition recognizeExperimentWorkbook(
            java.util.UUID organizationId, java.util.UUID actorId, java.util.UUID fileId,
            java.util.UUID originalFileId
    ) {
        var result = importService.recognizeForExperiment(organizationId, actorId, fileId, originalFileId);
        var suggestions = result.suggestions().stream()
                .filter(item -> !"REJECTED".equals(item.decision())
                        && !"IGNORED".equals(item.decision()))
                .map(item -> new Suggestion(item.suggestionType(), item.confidence(), item.payload(), item.evidence(), item.decision()))
                .toList();
        return new WorkbookRecognition(result.snapshot(), result.result(), new RecognitionBatch(suggestions));
    }

    @Override
    public WorkbookRecognition recognizeExperimentWorkbookSnapshot(
            java.util.UUID organizationId, java.util.UUID actorId, java.util.UUID snapshotFileId,
            java.util.UUID originalFileId
    ) {
        var result = importService.recognizeForExperimentSnapshot(
                organizationId, actorId, snapshotFileId, originalFileId);
        var suggestions = result.suggestions().stream()
                .filter(item -> !"REJECTED".equals(item.decision())
                        && !"IGNORED".equals(item.decision()))
                .map(item -> new Suggestion(item.suggestionType(), item.confidence(), item.payload(), item.evidence(), item.decision()))
                .toList();
        return new WorkbookRecognition(result.snapshot(), result.result(), new RecognitionBatch(suggestions));
    }

    @Override
    public byte[] exportWordSnapshot(byte[] source, JsonNode snapshot) {
        if (source == null || source.length == 0) {
            throw new IllegalArgumentException("Word 文档内容为空");
        }
        if (snapshot == null || !snapshot.isObject()) return source;
        return wordPatcher.applySnapshot(source, snapshot);
    }

    private ParseResult convert(OfficeStructureParser.ParseResult parsed) {
        return new ParseResult(parsed.structureSummary(), parsed.initialEditorSnapshot());
    }
}
