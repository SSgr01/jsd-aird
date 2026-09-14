package com.jsd.aird.spc.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.spc.application.port.SpectrumVisionClient;
import org.junit.jupiter.api.Test;

class SpectrumResponsesStreamParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpectrumResponsesStreamParser parser = new SpectrumResponsesStreamParser(objectMapper);

    @Test
    void parsesChineseAndEscapedJsonWhenUtf8BytesAreSplitArbitrarily() throws Exception {
        var output = "{\"answerMarkdown\":\"峰位约 295 nm，差异约 0.2 Abs。\",\"observations\":[]}";
        var stream = sse(
                event("response.reasoning_summary_text.delta", "\"item_id\":\"r1\",\"summary_index\":0,\"delta\":\"先核对峰位\""),
                event("response.reasoning_summary_text.done", "\"item_id\":\"r1\",\"summary_index\":0,\"text\":\"先核对峰位与强度。\""),
                event("response.output_text.delta", "\"delta\":" + objectMapper.writeValueAsString(output.substring(0, 24))),
                event("response.output_text.delta", "\"delta\":" + objectMapper.writeValueAsString(output.substring(24))),
                completed(output),
                "data: [DONE]\n\n");
        var deltas = new StringBuilder();
        var observer = new SpectrumVisionClient.StreamObserver() {
            @Override public void onOutputTextDelta(String delta) { deltas.append(delta); }
        };

        var parsed = parser.parse(oneByteAtATime(stream), observer);

        assertThat(parsed.outputText()).isEqualTo(output);
        assertThat(deltas.toString()).isEqualTo(output);
        assertThat(parsed.response().path("usage").path("total_tokens").asInt()).isEqualTo(42);
    }

    @Test
    void rejectsFailedIncompleteAndDisconnectedStreams() {
        assertThatThrownBy(() -> parser.parse(bytes(event("response.failed",
                "\"response\":{\"error\":{\"message\":\"provider failed\"}}")),
                SpectrumVisionClient.StreamObserver.NOOP)).hasMessageContaining("provider failed");
        assertThatThrownBy(() -> parser.parse(bytes(event("response.incomplete",
                "\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}")),
                SpectrumVisionClient.StreamObserver.NOOP)).hasMessageContaining("max_output_tokens");
        assertThatThrownBy(() -> parser.parse(bytes(event("response.output_text.delta", "\"delta\":\"partial\"")),
                SpectrumVisionClient.StreamObserver.NOOP)).hasMessageContaining("未完整结束");
    }

    private String completed(String output) throws Exception {
        return "data: {\"type\":\"response.completed\",\"response\":{" +
                "\"status\":\"completed\",\"model\":\"gpt-5.6-sol\",\"output\":[" +
                "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"先核对峰位与强度。\"}]}," +
                "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":" +
                objectMapper.writeValueAsString(output) + "}] }],\"usage\":{\"total_tokens\":42}}}\n\n";
    }

    private String event(String type, String fields) {
        return "data: {\"type\":\"" + type + "\"," + fields + "}\n\n";
    }

    private String sse(String... events) {
        return String.join("", events);
    }

    private ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private FilterInputStream oneByteAtATime(String value) {
        return new FilterInputStream(bytes(value)) {
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                return super.read(buffer, offset, Math.min(1, length));
            }
        };
    }
}
