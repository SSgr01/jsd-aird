package com.jsd.aird.tpl.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class ModelAuditPayloadCodecTest {

    @Test
    void roundTripsCompressedJsonPayloads() throws Exception {
        var objectMapper = new ObjectMapper();
        var codec = new ModelAuditPayloadCodec(objectMapper);
        var payload = objectMapper.readTree("""
                {"model":"qwen-plus","messages":[{"role":"user","content":"区域识别请求"}]}
                """);

        var compressed = codec.compress(payload);

        assertThat(compressed).isNotEmpty();
        assertThat(codec.decompress(compressed)).isEqualTo(payload);
    }
}
