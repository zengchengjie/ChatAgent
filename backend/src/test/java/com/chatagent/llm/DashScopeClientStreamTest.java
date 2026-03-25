package com.chatagent.llm;

import com.chatagent.config.DashScopeProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class DashScopeClientStreamTest {

    private MockWebServer server;
    private DashScopeClient client;

    @BeforeEach
    void setup() throws IOException {
        server = new MockWebServer();
        server.start();
        DashScopeProperties props = new DashScopeProperties();
        props.setApiKey("test-key");
        props.setBaseUrl("http://localhost:" + server.getPort() + "/v1");
        props.setModel("qwen-plus");
        props.setConnectTimeoutMs(5000);
        props.setReadTimeoutMs(30000);
        RestClient rc =
                RestClient.builder().baseUrl(props.getBaseUrl().replaceAll("/$", "")).build();
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        client = new DashScopeClient(rc, http, props, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void streamCompletion_emitsDeltas() {
        String chunk1 =
                """
                data: {"choices":[{"delta":{"content":"Hi"}}]}

                """;
        String chunk2 =
                """
                data: [DONE]

                """;
        server.enqueue(
                new MockResponse()
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(chunk1 + chunk2));

        ObjectMapper om = new ObjectMapper();
        ArrayNode messages = om.createArrayNode();
        messages.add(om.createObjectNode().put("role", "user").put("content", "hello"));

        List<String> deltas = new ArrayList<>();
        client.streamCompletion(messages, null, deltas::add);

        Assertions.assertEquals(1, deltas.size());
        Assertions.assertEquals("Hi", deltas.get(0));
    }
}
