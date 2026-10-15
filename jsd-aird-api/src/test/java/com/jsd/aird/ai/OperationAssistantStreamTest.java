package com.jsd.aird.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jsd.aird.ai.adapter.in.web.OperationAssistantController;
import com.jsd.aird.ai.application.ModelCircuitBreaker;
import com.jsd.aird.ai.application.operation.OperationManual;
import com.jsd.aird.ai.application.operation.OperationAssistantService;
import com.jsd.aird.ai.application.operation.OperationManualRetrievalService;
import com.jsd.aird.ai.application.operation.OperationManualRetrievalService.Evidence;
import com.jsd.aird.ai.application.operation.OperationManualRetrievalService.Retrieval;
import com.jsd.aird.iam.api.AuthorizationService;
import com.jsd.aird.iam.api.PermissionDecision;
import com.jsd.aird.shared.security.Actor;
import com.jsd.aird.shared.security.ActorContext;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

class OperationAssistantStreamTest {
    @Test
    void emptyProviderResponseProducesRetryableErrorWithAuthorizedHandbookFallback() throws Exception {
        var manual = new OperationManual(new ObjectMapper());
        var retrieval = mock(OperationManualRetrievalService.class);
        var chapter = manual.sections().stream().filter(section -> section.id().equals("experiment-import")).findFirst().orElseThrow();
        when(retrieval.retrieve(any(), any(), any(), any())).thenReturn(new Retrieval(List.of(new Evidence("H1", chapter)), "KEYWORD", ""));
        AuthorizationService authorization = check -> new PermissionDecision(check.permissionCode().equals("experiment.create"),
                check.permissionCode(), "ALLOW", "ALL", "TEST");
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.empty());
        var beans = new StaticListableBeanFactory();
        beans.addBean("client", ChatClient.builder(model));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var service = new OperationAssistantService(manual, retrieval, authorization, beans.getBeanProvider(ChatClient.Builder.class),
                    new ModelCircuitBreaker(3, 30), executor, "http://model.test", "test", "test-model");
            var mvc = MockMvcBuilders.standaloneSetup(new OperationAssistantController(service)).build();
            ActorContext.set(new Actor(UUID.randomUUID(), UUID.randomUUID(), "customer", "USER"));
            try {
                var result = mvc.perform(post("/api/v1/operation-assistant/qa/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"怎样导入实验记录？\",\"history\":[]}"))
                        .andExpect(request().asyncStarted()).andReturn();
                result.getAsyncResult(5000);
                var response = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse();
                assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("event:error", "AI_MODEL_EMPTY_RESPONSE",
                        "相关操作手册供参考", "/experiments/upload").doesNotContain("event:done");
            } finally { ActorContext.clear(); }
        }
    }

    @Test
    void realServiceStreamsThenReturnsValidatedPageLinksThroughTheController() throws Exception {
        var manual = new OperationManual(new ObjectMapper());
        var retrieval = mock(OperationManualRetrievalService.class);
        var chapter = manual.sections().stream().filter(section -> section.id().equals("experiment-import")).findFirst().orElseThrow();
        when(retrieval.retrieve(any(), any(), any(), any())).thenReturn(new Retrieval(List.of(new Evidence("H1", chapter)), "HYBRID", ""));
        AuthorizationService authorization = check -> new PermissionDecision(check.permissionCode().equals("experiment.create"),
                check.permissionCode(), "ALLOW", "ALL", "TEST");
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("选择实验数据模板，")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("点击开始上传。[[H1]]"))))));
        var beans = new StaticListableBeanFactory();
        beans.addBean("client", ChatClient.builder(model));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var service = new OperationAssistantService(manual, retrieval, authorization, beans.getBeanProvider(ChatClient.Builder.class),
                    new ModelCircuitBreaker(3, 30), executor, "http://model.test", "test", "test-model");
            var mvc = MockMvcBuilders.standaloneSetup(new OperationAssistantController(service)).build();
            ActorContext.set(new Actor(UUID.randomUUID(), UUID.randomUUID(), "customer", "USER"));
            try {
                var result = mvc.perform(post("/api/v1/operation-assistant/qa/stream").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"怎样导入实验记录？\",\"history\":[]}"))
                        .andExpect(request().asyncStarted()).andReturn();
                result.getAsyncResult(5000);
                var response = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse();
                assertThat(response.getContentAsString(StandardCharsets.UTF_8)).contains("event:meta", "event:delta", "event:done",
                        "实验数据模板", "/experiments/upload").doesNotContain("[[H1]]");
                verify(model).stream(any(Prompt.class));
            } finally { ActorContext.clear(); }
        }
    }
}
