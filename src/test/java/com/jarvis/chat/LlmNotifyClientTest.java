package com.jarvis.chat;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withAccepted;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class LlmNotifyClientTest {

    @Test
    void sendsInternalTokenWhenNotifyingSessionEnd() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LlmNotifyClient client = new LlmNotifyClient(
                builder.build(),
                new LlmProperties("http://localhost:8000", "http://localhost:8000"),
                "shared-internal-token");

        server.expect(once(), requestTo("http://localhost:8000/events/session-end"))
                .andExpect(header("X-Internal-Token", "shared-internal-token"))
                .andExpect(jsonPath("$.eventId").value("session-1:NEW_CONVERSATION"))
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.userId").value("3"))
                .andRespond(withAccepted());

        client.notifySessionEnd("session-1", "3", SessionEndReason.NEW_CONVERSATION);

        server.verify();
    }
}
