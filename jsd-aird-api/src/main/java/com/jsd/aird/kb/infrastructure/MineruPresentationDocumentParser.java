package com.jsd.aird.kb.infrastructure;

import java.io.InputStream;
import java.util.Locale;

import com.jsd.aird.kb.domain.DocumentParser;
import org.springframework.stereotype.Component;

/** Knowledge presentations always use the token-authenticated MinerU precise API. */
@Component
public final class MineruPresentationDocumentParser implements DocumentParser {

    private final MineruDocumentParser mineru;

    public MineruPresentationDocumentParser(MineruDocumentParser mineru) {
        this.mineru = mineru;
    }

    @Override
    public boolean supports(String fileName, String contentType) {
        var name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".ppt") || name.endsWith(".pptx");
    }

    @Override
    public boolean isConfigured() {
        return mineru.isPreciselyConfigured();
    }

    @Override
    public String unavailableReason() {
        return "PPT/PPTX 解析需要启用 MinerU 并配置精准接口 Token";
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName) {
        return parse(source, fileName, null);
    }

    @Override
    public ParsedDocument parse(InputStream source, String fileName, ParseContext context) {
        if (!supports(fileName, context == null ? null : context.contentType())) {
            throw MineruException.contract("当前解析器仅支持 PPT/PPTX 文件", null);
        }
        return mineru.parsePresentation(source, fileName, context);
    }
}
