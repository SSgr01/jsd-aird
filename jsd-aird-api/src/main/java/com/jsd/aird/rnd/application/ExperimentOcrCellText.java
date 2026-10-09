package com.jsd.aird.rnd.application;

import com.jsd.aird.kb.application.LatexEvidenceProjector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain text for spreadsheet cells while the MinerU source blocks keep their original markup. */
final class ExperimentOcrCellText {
    private static final Pattern INLINE_MATH = Pattern.compile("(?<!\\\\)\\$([^$\\r\\n]+)\\$");
    private final LatexEvidenceProjector latex = new LatexEvidenceProjector();

    String display(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('$') < 0) return raw;
        var matcher = INLINE_MATH.matcher(raw);
        var result = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(latex.project(matcher.group(1))));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
