package com.jsd.aird.tpl.application;

import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsd.aird.tpl.api.TemplateOfficeFacade;
import com.jsd.aird.tpl.application.port.OfficeStructureParser;
import com.jsd.aird.tpl.application.port.WorkbookSnapshotStructureParser;
import com.jsd.aird.tpl.domain.TemplateFormat;

import org.springframework.stereotype.Service;

/** Adapts template-center parsers to the stable cross-module API. */
@Service
public class TemplateOfficeFacadeImpl implements TemplateOfficeFacade {

    private final List<OfficeStructureParser> officeParsers;
    private final WorkbookSnapshotStructureParser snapshotParser;
    private final RuleBasedRecognitionEngine recognitionEngine;

    public TemplateOfficeFacadeImpl(
            List<OfficeStructureParser> officeParsers,
            WorkbookSnapshotStructureParser snapshotParser,
            RuleBasedRecognitionEngine recognitionEngine
    ) {
        this.officeParsers = List.copyOf(officeParsers);
        this.snapshotParser = snapshotParser;
        this.recognitionEngine = recognitionEngine;
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

    private ParseResult convert(OfficeStructureParser.ParseResult parsed) {
        return new ParseResult(parsed.structureSummary(), parsed.initialEditorSnapshot());
    }
}
