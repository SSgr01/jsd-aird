package com.jsd.aird.spc.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.spc.application.port.SpectrumVisionClient;
import org.junit.jupiter.api.Test;

class SpectrumResponsesStreamParserTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SpectrumResponsesStreamParser parser = new SpectrumResponsesStreamParser(objectMapper);

    @Test
    void streamsNaturalChineseMarkdownWhenUtf8BytesAreSplitArbitrarily() throws Exception {
        var output = "## 光谱对比\n\n主峰约 295 nm，图上估读差异约 0.2 Abs。\n\n| 曲线 | 峰高 |\n|---|---:|\n| 旧样 | 2.4 Abs |";
        var stream = sse(
                event("response.reasoning_summary_text.delta", "\"item_id\":\"r1\",\"summary_index\":0,\"delta\":\"先核对峰位\""),
                event("response.reasoning_summary_text.done", "\"item_id\":\"r1\",\"summary_index\":0,\"text\":\"先核对峰位与强度。\""),
                event("response.output_text.delta", "\"delta\":" + objectMapper.writeValueAsString(output.substring(0, 24))),
                event("response.output_text.delta", "\"delta\":" + objectMapper.writeValueAsString(output.substring(24))),
                completed(output),
                "data: [DONE]\n\n");
        var deltas = new StringBuilder();
        var chunks = new ArrayList<String>();
        var observer = new SpectrumVisionClient.StreamObserver() {
            @Override public void onOutputTextDelta(String delta) {
                chunks.add(delta);
                deltas.append(delta);
            }
        };

        var parsed = parser.parse(oneByteAtATime(stream), observer);

        assertThat(parsed.outputText()).isEqualTo(output);
        assertThat(deltas.toString()).isEqualTo(output);
        assertThat(chunks).containsExactly(output.substring(0, 24), output.substring(24));
        assertThat(parsed.response().path("usage").path("total_tokens").asInt()).isEqualTo(42);
    }

    @Test
    void publishesOutputDeltaBeforeTheProviderCompletesTheResponse() throws Exception {
        var output = "主峰约 295 nm。";
        var input = new PipedInputStream();
        var upstream = new PipedOutputStream(input);
        var deltaPublished = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        Future<SpectrumResponsesStreamParser.ParsedStream> parse = executor.submit(() ->
                parser.parse(input, new SpectrumVisionClient.StreamObserver() {
                    @Override
                    public void onOutputTextDelta(String delta) {
                        deltaPublished.countDown();
                    }
                }));
        try {
            upstream.write(event("response.output_text.delta",
                    "\"delta\":" + objectMapper.writeValueAsString(output)).getBytes(StandardCharsets.UTF_8));
            upstream.flush();

            assertThat(deltaPublished.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(parse.isDone()).isFalse();

            upstream.write(completed(output).getBytes(StandardCharsets.UTF_8));
            upstream.close();
            assertThat(parse.get(3, TimeUnit.SECONDS).outputText()).isEqualTo(output);
        } finally {
            upstream.close();
            executor.shutdownNow();
        }
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
