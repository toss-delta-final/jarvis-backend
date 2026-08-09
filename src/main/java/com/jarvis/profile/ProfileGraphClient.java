package com.jarvis.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.chat.LlmProperties;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.internal.InternalTokenFilter;
import java.net.URI;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * AI 취향 그래프 아웃바운드 (노션 I-32~I-37) — M-11~M-16의 프록시 대상이다.
 * 인증은 {@code X-Internal-Token} 공유 시크릿(05 §0), 사용자 AT는 <b>절대 AI로 넘기지 않는다</b>.
 *
 * <p><b>본문을 해석하지 않는다.</b> 요청·응답 모두 {@link JsonNode} 그대로 통과시킨다 —
 * 필드를 하나씩 DTO에 매핑하면 AI가 모양을 바꿀 때 <b>아무 에러 없이 조용히 사라진다</b>.
 * 이틀치 실례가 있다: 2026-08-08 노션 예시가 축약본이라 {@code lastConfirmedAt}·{@code truncated}·
 * {@code usagePolicy}가 누락돼 있었고, 2026-08-09 확정본에서 그 셋이 다시 빠지며 {@code nodes}
 * 배열이 사라지고 대상이 {@code edges[].object}로 인라인됐다. <b>DTO였으면 이틀 연속 고쳤어야 했다.</b>
 * 계약도 "data 는 I-32 응답 본문 그대로"라고 못박고 있다.
 * 값을 실제로 쓰는 I-22(홈 추천)가 타입 DTO를 쓰는 것과 기준이 다른 게 맞다 — 해석하면 DTO,
 * 통과하면 통과형.
 */
@Slf4j
@Component
public class ProfileGraphClient {

    private final RestClient llmRestClient;
    private final RestClient profileWriteRestClient;
    private final LlmProperties llmProperties;
    private final ObjectMapper objectMapper;
    private final String internalToken;

    public ProfileGraphClient(@Qualifier("llmRestClient") RestClient llmRestClient,
                              @Qualifier("profileWriteRestClient") RestClient profileWriteRestClient,
                              LlmProperties llmProperties, ObjectMapper objectMapper,
                              @Value("${app.internal.token:}") String internalToken) {
        this.llmRestClient = llmRestClient;
        this.profileWriteRestClient = profileWriteRestClient;
        this.llmProperties = llmProperties;
        this.objectMapper = objectMapper;
        this.internalToken = internalToken;
    }

    /** I-32 — 응답 예산 2s라 기본 클라이언트(3s)로 충분하다 */
    public JsonNode getGraph(long userId) {
        URI uri = uri("/internal/profile/{userId}/graph", userId);
        return execute("I-32", () -> llmRestClient.get()
                .uri(uri)
                .header(InternalTokenFilter.HEADER, internalToken)
                .retrieve()
                .body(JsonNode.class));
    }

    /** I-33 — {@code If-Match} 필수. 본문은 해석하지 않고 그대로 넘긴다 */
    public JsonNode updateEdge(long userId, String edgeId, JsonNode body, String ifMatch) {
        URI uri = uri("/internal/profile/{userId}/graph/edges/{edgeId}", userId, edgeId);
        return execute("I-33", () -> profileWriteRestClient.patch()
                .uri(uri)
                .header(InternalTokenFilter.HEADER, internalToken)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    /** I-34 — 본문 없음. 신원·대상이 전부 경로에 있다 */
    public JsonNode deleteEdge(long userId, String edgeId, String ifMatch) {
        URI uri = uri("/internal/profile/{userId}/graph/edges/{edgeId}", userId, edgeId);
        return execute("I-34", () -> profileWriteRestClient.delete()
                .uri(uri)
                .header(InternalTokenFilter.HEADER, internalToken)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .retrieve()
                .body(JsonNode.class));
    }

    /** I-36 — {@code scope} 검증은 AI가 한다. Spring은 본문을 그대로 전달한다 */
    public JsonNode reset(long userId, JsonNode body, String ifMatch) {
        URI uri = uri("/internal/profile/{userId}/graph/reset", userId);
        return execute("I-36", () -> profileWriteRestClient.post()
                .uri(uri)
                .header(InternalTokenFilter.HEADER, internalToken)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    /**
     * I-37 — {@code If-Match}가 <b>선택인 유일한 API</b>다. 토글은 마지막 의사가 이기는 게 자연스럽고,
     * 프라이버시 스위치가 백그라운드 작업 때문에 잠기면 안 된다. 없으면 헤더를 아예 붙이지 않는다.
     */
    public JsonNode setPersonalization(long userId, JsonNode body, String ifMatch) {
        URI uri = uri("/internal/profile/{userId}/personalization", userId);
        return execute("I-37", () -> profileWriteRestClient.put()
                .uri(uri)
                .header(InternalTokenFilter.HEADER, internalToken)
                .headers(headers -> {
                    if (ifMatch != null && !ifMatch.isBlank()) {
                        headers.set(HttpHeaders.IF_MATCH, ifMatch);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class));
    }

    private URI uri(String path, Object... vars) {
        return URI.create(UriComponentsBuilder.fromUriString(llmProperties.baseUrl())
                .path(path)
                .buildAndExpand(vars)
                .encode()
                .toUriString());
    }

    private JsonNode execute(String api, Supplier<JsonNode> call) {
        if (llmProperties.baseUrl() == null || llmProperties.baseUrl().isBlank()) {
            // 조회·변경 모두 AI가 정본이라 대체할 값이 없다 — P-5처럼 인기상품으로 우회할 수 없다
            log.warn("{} 호출 불가 — LLM baseUrl 미설정", api);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
        try {
            return call.get();
        } catch (HttpStatusCodeException e) {
            throw translate(api, e);
        } catch (RestClientException e) {
            // 타임아웃·연결 실패. AI의 503·504와 함께 INTERNAL_ERROR로 접힌다(노션 M-11~M-16)
            log.warn("{} 호출 실패 — {}", api, e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /**
     * 레인 공통 코드(인증·검증·서버 오류)는 변환하고 <b>자원 전용 코드는 보존</b>한다 —
     * C-2↔I-2가 {@code CART_*}를 공유하고 인증 코드만 달리한 것과 같다. {@code PROFILE_*}를 살려야
     * FE가 두 종류의 409를 구분한다(하나는 재조회 후 재시도, 하나는 재시도해도 소용없음).
     *
     * <p>{@code detail}은 위치를 그대로 유지해 넘긴다 — {@code PROFILE_VERSION_CONFLICT}의
     * {@code detail.graphVersion}으로 FE가 곧바로 재조회한다.
     */
    private BusinessException translate(String api, HttpStatusCodeException e) {
        JsonNode error = errorBody(api, e.getResponseBodyAsString());
        String code = error.path("code").asText(null);
        JsonNode detail = error.path("detail");

        ErrorCode mapped = switch (code == null ? "" : code) {
            case "PROFILE_EDGE_NOT_FOUND" -> ErrorCode.PROFILE_EDGE_NOT_FOUND;
            case "PROFILE_VERSION_CONFLICT" -> ErrorCode.PROFILE_VERSION_CONFLICT;
            case "PROFILE_EDGE_NOT_EDITABLE" -> ErrorCode.PROFILE_EDGE_NOT_EDITABLE;
            case "BAD_REQUEST" -> ErrorCode.VALIDATION_ERROR;
            // 계약이 지정한 매핑이다. 실제로는 우리 쪽 시크릿 설정 문제라 사용자가 할 수 있는 게 없다
            case "INTERNAL_TOKEN_INVALID" -> ErrorCode.AUTH_REQUIRED;
            default -> byStatus(api, e);
        };
        return detail.isMissingNode() || detail.isNull()
                ? new BusinessException(mapped)
                : new BusinessException(mapped, detail);
    }

    /** 코드가 없거나 모르는 값일 때의 폴백 — 상태코드만으로 최선을 고른다 */
    private ErrorCode byStatus(String api, HttpStatusCodeException e) {
        log.warn("{} 알 수 없는 오류 응답 — status={}, body={}",
                api, e.getStatusCode(), e.getResponseBodyAsString());
        return switch (e.getStatusCode().value()) {
            case 400 -> ErrorCode.VALIDATION_ERROR;
            case 401 -> ErrorCode.AUTH_REQUIRED;
            case 404 -> ErrorCode.PROFILE_EDGE_NOT_FOUND;
            // 409는 재시도가 통하는 쪽으로 붙인다 — 반대로 틀리면 FE가 되살릴 방법이 없다
            case 409 -> ErrorCode.PROFILE_VERSION_CONFLICT;
            default -> ErrorCode.INTERNAL_ERROR;
        };
    }

    /**
     * AI 오류 본문은 <b>{@code {"error": {...}}} 봉투로 확정</b>됐다(2026-08-09 AI팀 확답).
     * 봉투가 아니면 <b>읽지 않는다</b> — 계약이 정해진 뒤에도 다른 모양을 받아주면
     * "둘 다 유효한가?"가 코드에 남아 다음 사람이 헷갈린다.
     *
     * <p>봉투가 아닌 본문이 오면 {@code code}를 못 읽어 {@link #byStatus} 폴백으로 떨어지고
     * {@code detail}도 실리지 않는다 — <b>그건 AI 쪽 계약 위반이지 우리 쪽 처리 실패가 아니다.</b>
     * 대신 조용히 지나가지 않도록 경고를 남긴다. 로그가 원인을 바로 가리켜야 한다.
     */
    private JsonNode errorBody(String api, String body) {
        try {
            JsonNode root = objectMapper.readTree(body == null ? "" : body);
            JsonNode wrapped = root.path("error");
            if (wrapped.isObject()) {
                return wrapped;
            }
            log.warn("{} 오류 본문이 error 봉투가 아니다 — 2026-08-09 확정 위반. code·detail을 읽지 못한다. body={}",
                    api, body);
            return objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }
}
