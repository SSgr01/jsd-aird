package com.jsd.aird.kb.infrastructure;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleMetadataModelTest {
    final ObjectMapper json=new ObjectMapper();
    OpenAiCompatibleMetadataModel model(String base,String key,String version) {
        return new OpenAiCompatibleMetadataModel(true,true,base,key,"test-chat-model",Duration.ofSeconds(5),version,1024,json);
    }
    @Test void localServicesNeedNoKeyAndReceiveBoundedSourcesWithOneIdempotentRequest() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var calls=new AtomicInteger();var body=new AtomicReference<JsonNode>();var authorization=new AtomicReference<String>();var idempotency=new AtomicReference<String>();
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();body.set(json.readTree(exchange.getRequestBody()));authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));idempotency.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            byte[] response="{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"{\\\"fields\\\":[],\\\"extra_fields\\\":[]}\"}}],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":12}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);try(var output=exchange.getResponseBody()){output.write(response);}
        });server.start();
        try {
            var model=model("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/","","v1");var request=UUID.randomUUID();
            var result=model.extract(json.createArrayNode().add(json.createObjectNode().put("source",0).put("text","Original material")),request);
            assertThat(calls.get()).isEqualTo(1);assertThat(authorization.get()).isNull();assertThat(idempotency.get()).isEqualTo(request.toString());
            assertThat(body.get().path("model").asText()).isEqualTo("test-chat-model");assertThat(body.get().path("response_format").path("type").asText()).isEqualTo("json_object");
            assertThat(body.get().path("messages").get(1).path("content").asText()).contains("Original material");
            assertThat(result.path("_provider_usage").path("prompt_tokens").asInt()).isEqualTo(50);
        } finally {server.stop(0);}
    }
    @Test void providerErrorsNeverCauseAutomaticRetriesAndKeysAreOptionalButSentWhenConfigured() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();var key=new AtomicReference<String>();
        server.createContext("/v1/chat/completions",exchange->{calls.incrementAndGet();key.set(exchange.getRequestHeaders().getFirst("Authorization"));exchange.sendResponseHeaders(503,-1);exchange.close();});server.start();
        try {
            var model=model("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test-key","v1");
            assertThatThrownBy(()->model.extract(json.createArrayNode(),UUID.randomUUID())).hasMessage("Metadata provider HTTP 503");
            assertThat(calls.get()).isEqualTo(1);assertThat(key.get()).isEqualTo("Bearer test-key");
        } finally {server.stop(0);}
    }
    @Test void credentialsDoNotInvalidateCacheButPromptVersionsAndModelsDo() {
        assertThat(model("http://localhost/v1","a","v1").policyHash()).isEqualTo(model("http://localhost/v1","b","v1").policyHash());
        assertThat(model("http://localhost/v1","a","v1").policyHash()).isNotEqualTo(model("http://localhost/v1","a","v2").policyHash());
        assertThatThrownBy(()->model("http://user:secret@localhost/v1","","v1")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void aStalledResponseBodyIsAlsoSubjectToTheTimeout() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var calls=new AtomicInteger();
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();exchange.sendResponseHeaders(200,1000);
            try(var output=exchange.getResponseBody()) {
                output.write('{');output.flush();
                try {Thread.sleep(1500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            } catch(java.io.IOException ignored) { }
        });server.start();
        try {
            var model=new OpenAiCompatibleMetadataModel(true,true,"http://127.0.0.1:"+server.getAddress().getPort()+"/v1","","test-model",Duration.ofMillis(500),"v1",1024,json);
            assertThatThrownBy(()->model.extract(json.createArrayNode(),UUID.randomUUID())).isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(java.io.IOException.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally {server.stop(0);}
    }
}
