package com.jsd.aird.kb.infrastructure;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import com.jsd.aird.kb.domain.DocumentParser;
import org.springframework.stereotype.Component;

@Component
public class PlainTextDocumentParser implements DocumentParser {

    @Override
    public boolean supports(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        var type = contentType == null ? "" : contentType.toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".csv") || name.endsWith(".md")
                || type.startsWith("text/");
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName) {
        var blocks = new ArrayList<TextBlock>();
        try (var reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            var line = new StringBuilder();
            String value;
            while ((value = reader.readLine()) != null) {
                if (value.isBlank() && !line.isEmpty()) {
                    blocks.add(new TextBlock(null, null, line.toString().trim()));
                    line.setLength(0);
                } else if (!value.isBlank()) {
                    if (!line.isEmpty()) line.append('\n');
                    line.append(value);
                }
            }
            if (!line.isEmpty()) blocks.add(new TextBlock(null, null, line.toString().trim()));
        } catch (Exception exception) {
            throw new IllegalStateException("文本文件解析失败", exception);
        }
        return new ParsedDocument(blocks, "text-v1");
    }
}
