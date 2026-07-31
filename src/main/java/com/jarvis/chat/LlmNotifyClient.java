package com.jarvis.chat;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.internal.InternalTokenFilter;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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

    /**
     * I-23 세션 소유권 승격 (CH-7이 유일한 발화 지점) — I-20과 달리 <b>동기·비-best-effort</b>다.
     * AI가 활성 스트림이 있으면 409로 거부하므로(jarvis-ai #187) 결과를 CH-7 응답에 그대로 옮겨야
     * FE가 재시도할지 새 대화로 갈지 정할 수 있다. 성공해야만 호출측이 Redis owner를 옮긴다 —
     * 순서를 뒤집으면 AI가 거부했을 때 "Spring은 회원, AI는 게스트"인 부분 성공이 고착된다.
     */
    public void notifySessionClaim(String sessionId, String guestId, long userId) {
        if (llmProperties.baseUrl() == null || llmProperties.baseUrl().isBlank()) {
            return; // FastAPI 미기동(로컬) — 승계는 Spring 쪽만 반영하고 넘어간다
        }
        try {
            llmRestClient.post()
                    .uri(llmProperties.baseUrl() + "/events/session-claim")
                    .header(InternalTokenFilter.HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("sessionId", sessionId, "guestId", guestId, "userId", userId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.Conflict e) {
            throw new BusinessException(conflictCode(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            log.warn("I-23 세션 승계 실패 — sessionId={}, guestId={}, userId={}: {}",
                    sessionId, guestId, userId, e.getMessage());
            throw new BusinessException(ErrorCode.SESSION_CLAIM_UNAVAILABLE);
        }
    }

    /**
     * AI의 409는 세 가지다 — SESSION_ACTIVE만 "스트리밍 끝나고 재시도"가 통하고,
     * FINALIZING·CLAIM_CONFLICT는 되돌릴 수 없어 새 세션으로 가야 한다(노션 I-23).
     */
    private static ErrorCode conflictCode(String body) {
        return body != null && body.contains("SESSION_ACTIVE")
                ? ErrorCode.SESSION_ACTIVE
                : ErrorCode.SESSION_CLAIM_CONFLICT;
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
