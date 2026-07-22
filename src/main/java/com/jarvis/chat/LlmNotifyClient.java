package com.jarvis.chat;

import com.jarvis.internal.InternalTokenFilter;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * I-20 세션 종료 통지 (05 §2-1 — 방향 예외: Spring→FastAPI). 멱등이라 실패해도 무해 —
 * 재시도 없이 warn 로그만 남긴다(FastAPI 자체 TTL이 백스톱, 05 §3). 게스트는 프로필 대상이
 * 아니라 호출 자체를 생략하므로(호출측 책임) userId는 항상 회원 BIGINT다(노션 I-20 정본).
 * 인증은 {@code X-Internal-Token} 공유 시크릿 — 인바운드 {@link InternalTokenFilter}와 동일 값(05 §0).
 */
@Slf4j
@Component
public class LlmNotifyClient {

    private final RestClient llmRestClient;
    private final LlmProperties llmProperties;
    private final String internalToken;

    public LlmNotifyClient(RestClient llmRestClient, LlmProperties llmProperties,
                           @Value("${app.internal.token:}") String internalToken) {
        this.llmRestClient = llmRestClient;
        this.llmProperties = llmProperties;
        this.internalToken = internalToken;
    }

    @Async
    public void notifySessionEnd(String sessionId, long userId, SessionEndReason reason) {
        if (llmProperties.baseUrl() == null || llmProperties.baseUrl().isBlank()) {
            return; // FastAPI 미기동(로컬) — 통지 생략
        }
        try {
            llmRestClient.post()
                    .uri(llmProperties.baseUrl() + "/events/session-end")
                    .header(InternalTokenFilter.HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("sessionId", sessionId, "userId", userId, "reason", reason.wireValue()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("I-20 세션 종료 통지 실패(무시) — sessionId={}, userId={}, reason={}: {}",
                    sessionId, userId, reason, e.getMessage());
        }
    }
}
