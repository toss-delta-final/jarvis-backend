package com.jarvis.chat;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * I-20 세션 종료 통지 (05 §2-1 — 방향 예외: Spring→FastAPI). 멱등이라 실패해도 무해 —
 * 재시도 없이 warn 로그만 남긴다(FastAPI 자체 TTL이 백스톱, 05 §3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmNotifyClient {

    private final RestClient llmRestClient;
    private final LlmProperties llmProperties;

    @Async
    public void notifySessionEnd(String sessionId, SessionEndReason reason) {
        if (llmProperties.baseUrl() == null || llmProperties.baseUrl().isBlank()) {
            return; // FastAPI 미기동(로컬) — 통지 생략
        }
        try {
            llmRestClient.post()
                    .uri(llmProperties.baseUrl() + "/events/session-end")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("sessionId", sessionId, "reason", reason.name()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("I-20 세션 종료 통지 실패(무시) — sessionId={}, reason={}: {}",
                    sessionId, reason, e.getMessage());
        }
    }
}
