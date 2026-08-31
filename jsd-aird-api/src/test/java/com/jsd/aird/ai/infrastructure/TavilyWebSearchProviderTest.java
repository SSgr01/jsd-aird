package com.jsd.aird.ai.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.jsd.aird.ai.application.port.WebSearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TavilyWebSearchProviderTest {

    @Test
    void usesBearerAuthenticationAndMapsSearchMetadata() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://api.tavily.com/search"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andExpect(jsonPath("$.query").value("public query"))
                .andExpect(jsonPath("$.search_depth").value("advanced"))
                .andExpect(jsonPath("$.include_answer").value(false))
                .andExpect(jsonPath("$.include_raw_content").value(false))
                .andExpect(jsonPath("$.include_images").value(false))
                .andExpect(jsonPath("$.topic").value("news"))
                .andExpect(jsonPath("$.time_range").value("month"))
                .andExpect(jsonPath("$.api_key").doesNotExist())
                .andRespond(withSuccess("""
                        {"response_time":0.12,"results":[{"title":"Public source","url":"https://example.com/source",
                        "content":"verified public content","score":0.91,"published_date":"2026-08-01"}]}
                        """, MediaType.APPLICATION_JSON));
        var provider = new TavilyWebSearchProvider(true, "https://api.tavily.com", "test-key", 5,
                builder.build());

        var result = provider.search(new WebSearchProvider.SearchQuery("public query", "NEWS", "MONTH"));

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.providerResponseMs()).isEqualTo(120);
        assertThat(result.results()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("Public source");
            assertThat(item.score()).isEqualTo(0.91);
            assertThat(item.publishedAt()).isEqualTo("2026-08-01");
        });
        server.verify();
    }
}
