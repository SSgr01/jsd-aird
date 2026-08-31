package com.jsd.aird.kb.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** A deliberately small scanner for MinerU math delimiters and exact sup/sub tokens. */
final class MineruInlineSemanticParser {

    private static final Set<Character> MARKDOWN_ESCAPES = Set.of(
            '*', '_', '[', ']', '(', ')', '#', '+', '-', '.', '!', '%', '$');

    private final ObjectMapper mapper;
    private final LatexEvidenceProjector latexProjector;

    MineruInlineSemanticParser(ObjectMapper mapper, LatexEvidenceProjector latexProjector) {
        this.mapper = mapper;
        this.latexProjector = latexProjector;
    }

    List<RichBlock> parseBlocks(String value, boolean code, boolean declaredFormula) {
        var source = value == null ? "" : value;
        if (code) return List.of(new RichBlock(BlockKind.TEXT, List.of(new Text(source, Set.of())), null));
        if (declaredFormula) return List.of(new RichBlock(BlockKind.FORMULA, List.of(), stripWholeBlockDelimiter(source)));
        var blocks = splitBlockMath(source);
        if (blocks.stream().noneMatch(block -> block.kind() == BlockKind.FORMULA)) {
            return List.of(new RichBlock(BlockKind.TEXT, parseInline(source), null));
        }
        return blocks;
    }

    ArrayNode inlineJson(String value, boolean code) {
        return inlineJson(code ? List.of(new Text(value == null ? "" : value, Set.of())) : parseInline(value));
    }

    ArrayNode inlineJson(List<Inline> values) {
        var result = mapper.createArrayNode();
        for (var value : values) {
            if (value instanceof Text text && !text.value().isEmpty()) {
                var node = mapper.createObjectNode().put("type", "text").put("text", text.value());
                if (!text.marks().isEmpty()) {
                    var marks = mapper.createArrayNode();
                    if (text.marks().contains(Script.SUPERSCRIPT)) marks.addObject().put("type", "superscript");
                    if (text.marks().contains(Script.SUBSCRIPT)) marks.addObject().put("type", "subscript");
                    node.set("marks", marks);
                }
                result.add(node);
            } else if (value instanceof Math math) {
                result.addObject().put("type", "inlineMath").putObject("attrs").put("latexRaw", math.latexRaw());
            }
        }
        return result;
    }

    String evidenceText(JsonNode node) {
        var type = node.path("type").asText();
        if ("inlineMath".equals(type)) return latexProjector.project(node.path("attrs").path("latexRaw").asText(""));
        if ("formula".equals(type)) {
            var raw = node.path("attrs").path("latexRaw").asText("");
            if (raw.isBlank()) raw = legacyText(node);
            return latexProjector.project(raw);
        }
        if ("text".equals(type)) {
            var value = node.path("text").asText("");
            var superscript = hasMark(node, "superscript");
            var subscript = hasMark(node, "subscript");
            if (superscript != subscript) {
                var converted = LatexEvidenceProjector.unicodeScript(value, superscript);
                if (converted != null) return converted;
            }
            return value;
        }
        var result = new StringBuilder();
        node.path("content").forEach(child -> result.append(evidenceText(child)));
        return result.toString();
    }

    private String legacyText(JsonNode node) {
        var result = new StringBuilder();
        node.path("content").forEach(child -> result.append(child.path("text").asText("")));
        return result.toString();
    }

    private boolean hasMark(JsonNode node, String type) {
        for (var mark : node.path("marks")) if (type.equals(mark.path("type").asText())) return true;
        return false;
    }

    private List<RichBlock> splitBlockMath(String source) {
        var result = new ArrayList<RichBlock>();
        var emitted = 0;
        var search = 0;
        while (search < source.length()) {
            var opening = nextBlockOpening(source, search);
            if (opening == null) break;
            var closing = findClosing(source, opening.contentStart(), opening.close());
            if (closing < 0) { search = opening.start() + opening.open().length(); continue; }
            addTextBlock(result, source.substring(emitted, opening.start()));
            result.add(new RichBlock(BlockKind.FORMULA, List.of(), source.substring(opening.contentStart(), closing)));
            emitted = closing + opening.close().length();
            search = emitted;
        }
        addTextBlock(result, source.substring(emitted));
        return result.isEmpty() ? List.of(new RichBlock(BlockKind.TEXT, parseInline(source), null)) : List.copyOf(result);
    }

    private void addTextBlock(List<RichBlock> output, String value) {
        var text = value.strip();
        if (!text.isEmpty()) output.add(new RichBlock(BlockKind.TEXT, parseInline(text), null));
    }

    private Opening nextBlockOpening(String source, int from) {
        for (var index = from; index < source.length(); index++) {
            if (source.startsWith("$$", index) && !isEscaped(source, index)) return new Opening(index, "$$", "$$");
            if (source.startsWith("\\[", index) && !isEscaped(source, index)) return new Opening(index, "\\[", "\\]");
        }
        return null;
    }

    private int findClosing(String source, int from, String delimiter) {
        for (var index = from; index <= source.length() - delimiter.length(); index++) {
            if (source.startsWith(delimiter, index) && !isEscaped(source, index)) return index;
        }
        return -1;
    }

    private String stripWholeBlockDelimiter(String value) {
        var trimmed = value.strip();
        for (var delimiters : List.of(new String[]{"$$", "$$"}, new String[]{"\\[", "\\]"})) {
            if (trimmed.startsWith(delimiters[0]) && trimmed.endsWith(delimiters[1])
                    && trimmed.length() >= delimiters[0].length() + delimiters[1].length()) {
                return trimmed.substring(delimiters[0].length(), trimmed.length() - delimiters[1].length());
            }
        }
        return trimmed;
    }

    private List<Inline> parseInline(String value) {
        var source = value == null ? "" : value;
        var result = new ArrayList<Inline>();
        var text = new StringBuilder();
        for (var index = 0; index < source.length();) {
            if (source.startsWith("\\$", index) && !isEscaped(source, index)) {
                text.append('$'); index += 2; continue;
            }
            if (source.startsWith("\\(", index) && !isEscaped(source, index)) {
                var closing = findClosing(source, index + 2, "\\)");
                if (closing >= 0) {
                    flushText(result, text);
                    result.add(new Math(source.substring(index + 2, closing)));
                    index = closing + 2;
                    continue;
                }
            }
            if (source.charAt(index) == '$' && !isEscaped(source, index)) {
                if (source.startsWith("$$", index)) { text.append("$$"); index += 2; continue; }
                if (index + 1 < source.length() && !Character.isWhitespace(source.charAt(index + 1))) {
                    var closing = inlineDollarClosing(source, index + 1);
                    if (closing >= 0) {
                        flushText(result, text);
                        result.add(new Math(source.substring(index + 1, closing)));
                        index = closing + 1;
                        continue;
                    }
                }
            }
            text.append(source.charAt(index++));
        }
        flushText(result, text);
        return List.copyOf(result);
    }

    private int inlineDollarClosing(String source, int from) {
        for (var index = from; index < source.length(); index++) {
            if (source.charAt(index) == '\n') return -1;
            if (source.charAt(index) != '$' || isEscaped(source, index)) continue;
            if (index + 1 < source.length() && source.charAt(index + 1) == '$') { index++; continue; }
            if (index > 0 && source.charAt(index - 1) == '$') continue;
            if (!Character.isWhitespace(source.charAt(index - 1))
                    && (index + 1 >= source.length() || !Character.isDigit(source.charAt(index + 1)))) return index;
        }
        return -1;
    }

    private void flushText(List<Inline> output, StringBuilder buffer) {
        if (buffer.isEmpty()) return;
        output.addAll(parseScripts(buffer.toString()));
        buffer.setLength(0);
    }

    private List<Inline> parseScripts(String source) {
        var tokens = scriptTokens(source);
        var stack = new ArrayDeque<Integer>();
        var valid = new boolean[tokens.size()];
        for (var index = 0; index < tokens.size(); index++) {
            var token = tokens.get(index);
            if (token.opening()) stack.push(index);
            else if (!stack.isEmpty() && tokens.get(stack.peek()).script() == token.script()) {
                valid[index] = true;
                valid[stack.pop()] = true;
            } else stack.clear();
        }
        var result = new ArrayList<Inline>();
        var active = EnumSet.noneOf(Script.class);
        var cursor = 0;
        for (var index = 0; index < tokens.size(); index++) {
            var token = tokens.get(index);
            addText(result, source.substring(cursor, token.start()), active);
            if (valid[index]) {
                if (token.opening()) active.add(token.script()); else active.remove(token.script());
            }
            cursor = token.end();
        }
        addText(result, source.substring(cursor), active);
        return result;
    }

    private List<ScriptToken> scriptTokens(String source) {
        var result = new ArrayList<ScriptToken>();
        for (var index = 0; index < source.length();) {
            var token = tokenAt(source, index);
            if (token == null) { index++; continue; }
            result.add(token);
            index = token.end();
        }
        return result;
    }

    private ScriptToken tokenAt(String source, int index) {
        if (source.startsWith("<sup>", index)) return new ScriptToken(index, index + 5, Script.SUPERSCRIPT, true);
        if (source.startsWith("</sup>", index)) return new ScriptToken(index, index + 6, Script.SUPERSCRIPT, false);
        if (source.startsWith("<sub>", index)) return new ScriptToken(index, index + 5, Script.SUBSCRIPT, true);
        if (source.startsWith("</sub>", index)) return new ScriptToken(index, index + 6, Script.SUBSCRIPT, false);
        return null;
    }

    private void addText(List<Inline> output, String source, Set<Script> marks) {
        var value = unescapeMarkdown(source);
        if (value.isEmpty()) return;
        var immutableMarks = marks.isEmpty() ? Set.<Script>of() : Set.copyOf(marks);
        if (!output.isEmpty() && output.getLast() instanceof Text previous && previous.marks().equals(immutableMarks)) {
            output.set(output.size() - 1, new Text(previous.value() + value, immutableMarks));
        } else output.add(new Text(value, immutableMarks));
    }

    private String unescapeMarkdown(String source) {
        var result = new StringBuilder();
        for (var index = 0; index < source.length(); index++) {
            var value = source.charAt(index);
            if (value == '\\' && index + 1 < source.length() && MARKDOWN_ESCAPES.contains(source.charAt(index + 1))) {
                result.append(source.charAt(++index));
            } else result.append(value);
        }
        return result.toString();
    }

    private boolean isEscaped(String source, int index) {
        var slashes = 0;
        for (var cursor = index - 1; cursor >= 0 && source.charAt(cursor) == '\\'; cursor--) slashes++;
        return slashes % 2 == 1;
    }

    enum BlockKind { TEXT, FORMULA }
    enum Script { SUPERSCRIPT, SUBSCRIPT }
    sealed interface Inline permits Text, Math { }
    record Text(String value, Set<Script> marks) implements Inline { }
    record Math(String latexRaw) implements Inline { }
    record RichBlock(BlockKind kind, List<Inline> inline, String latexRaw) { }
    private record Opening(int start, String open, String close) {
        int contentStart() { return start + open.length(); }
    }
    private record ScriptToken(int start, int end, Script script, boolean opening) { }
}
