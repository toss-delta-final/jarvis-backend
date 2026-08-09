package com.jarvis.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.chat.LlmProperties;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.internal.InternalTokenFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * I-32~I-37 아웃바운드 (노션) — 이 클래스가 지켜야 할 두 가지를 고정한다:
 * <b>본문 무손실 통과</b>와 <b>PROFILE_* 코드 보존</b>.
 */
class ProfileGraphClientTest {

    private static final long USER_ID = 7L;
    private static final String EDGE_ID = "e_2f80d1aa63b74c19";
    private static final String BASE_URL = "http://ai.test";
    private static final String TOKEN = "test-internal-token";

    private MockRestServiceServer server;
    private ProfileGraphClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        client = new ProfileGraphClient(restClient, restClient,
                new LlmProperties(BASE_URL, null), new ObjectMapper(), TOKEN);
    }

    @Test
    @DisplayName("응답 본문을 손실 없이 통과시킨다 — 계약에 없는 필드도 살아남아야 한다")
    void passesResponseThrough() {
        // 이틀 새 두 번(8/8 3필드 추가, 8/9 그 셋 제거 + nodes→object 인라인) 모양이 바뀐 회귀 테스트다.
        // DTO로 매핑하면 여기서 아무 에러 없이 필드가 사라진다. 아래 본문은 2026-08-09 확정 모양 +
        // 계약에 없는 필드 하나 — 계약이 또 바뀌어도 이 테스트는 그대로 통과해야 한다.
        server.expect(requestTo(BASE_URL + "/internal/profile/7/graph"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(InternalTokenFilter.HEADER, TOKEN))
                .andRespond(withSuccess("""
                        {"userId":123,"exists":true,"graphVersion":"g42",
                         "personalization":{"enabled":true},
                         "edges":[{"edgeId":"e_1","predicate":"avoids",
                                   "object":{"nodeId":"brand:소니","type":"brand","label":"소니"},
                                   "editable":true,"challenged":false}],
                         "계약에없는필드":42}""", MediaType.APPLICATION_JSON));

        JsonNode body = client.getGraph(USER_ID);

        // 중첩 객체가 통째로 살아남는다 — nodes 룩업 없이 object가 인라인인 현행 모양
        assertThat(body.path("edges").get(0).path("object").path("label").asText())
                .isEqualTo("소니");
        assertThat(body.path("personalization").path("enabled").asBoolean()).isTrue();
        assertThat(body.path("계약에없는필드").asInt()).isEqualTo(42);
    }

    @Test
    @DisplayName("409 버전 충돌 — 코드를 보존하고 detail.graphVersion을 그대로 넘긴다")
    void preservesVersionConflictDetail() {
        server.expect(requestTo(BASE_URL + "/internal/profile/7/graph/edges/" + EDGE_ID))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header(HttpHeaders.IF_MATCH, "\"g42\""))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":{"code":"PROFILE_VERSION_CONFLICT",
                                          "detail":{"graphVersion":"g44"}}}"""));

        assertThatThrownBy(() -> client.deleteEdge(USER_ID, EDGE_ID, "\"g42\""))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.PROFILE_VERSION_CONFLICT);
                    assertThat(((JsonNode) be.getDetail()).path("graphVersion").asText())
                            .isEqualTo("g44");
                });
    }

    @Test
    @DisplayName("봉투가 아닌 본문은 읽지 않는다 — 상태코드 폴백으로 떨어지고 detail도 없다 (계약 위반의 결과)")
    void ignoresNonEnvelopeErrorBody() {
        server.expect(requestTo(BASE_URL + "/internal/profile/7/graph/edges/" + EDGE_ID))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"PROFILE_VERSION_CONFLICT\",\"detail\":{\"graphVersion\":\"g44\"}}"));

        // 봉투 확정(2026-08-09) 이후 평문은 AI 쪽 위반이다. 코드가 우연히 맞는 건 409 폴백 덕이지
        // 본문을 읽어서가 아니고, detail이 없어 FE는 graphVersion을 받지 못한다 — 로그가 원인을 가리킨다
        assertThatThrownBy(() -> client.deleteEdge(USER_ID, EDGE_ID, "\"g42\""))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getErrorCode()).isEqualTo(ErrorCode.PROFILE_VERSION_CONFLICT);
                    assertThat(be.getDetail()).isNull();
                });
    }

    @Test
    @DisplayName("같은 409라도 수정 불가는 다른 코드로 — FE 대응이 반대라 합치면 안 된다")
    void distinguishesNotEditable() {
        server.expect(requestTo(BASE_URL + "/internal/profile/7/graph/edges/" + EDGE_ID))
                .andExpect(method(HttpMethod.PATCH))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"PROFILE_EDGE_NOT_EDITABLE\"}}"));

        assertThatThrownBy(() -> client.updateEdge(USER_ID, EDGE_ID,
                new ObjectMapper().createObjectNode(), "\"g42\""))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PROFILE_EDGE_NOT_EDITABLE);
    }

    @Test
    @DisplayName("레인 공통 코드는 변환한다 — INTERNAL_TOKEN_INVALID → AUTH_REQUIRED")
    void translatesLaneCodes() {
        server.expect(requestTo(BASE_URL + "/internal/profile/7/graph"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"INTERNAL_TOKEN_INVALID\"}}"));

        assertThatThrownBy(() -> client.getGraph(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_REQUIRED);
    }

    @Test
    @DisplayName("AI의 400은 VALIDATION_ERROR로 접는다")
    void translatesBadRequest() {
        server.expect(requestTo(BASE_URL + "/internal/profile/7/graph/reset"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"BAD_REQUEST\"}}"));

        assertThatThrownBy(() -> client.reset(USER_ID,
                new ObjectMapper().createObjectNode(), "\"g45\""))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("AI의 503·504는 INTERNAL_ERROR로 접는다 — 계약이 500에 포함시켰다")
    void foldsUpstreamFailures() {
        server.expect(requestTo(BASE_URL + "/internal/profile/7/graph"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":{\"code\":\"UPSTREAM_UNAVAILABLE\"}}"));

        assertThatThrownBy(() -> client.getGraph(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    @DisplayName("M-16은 If-Match가 없으면 헤더를 아예 붙이지 않는다")
    void omitsIfMatchWhenAbsent() {
        server.expect(requestTo(BASE_URL + "/internal/profile/7/personalization"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(request -> assertThat(request.getHeaders().containsKey(HttpHeaders.IF_MATCH))
                        .as("If-Match 미지정 시 빈 헤더를 보내면 AI가 400을 낸다")
                        .isFalse())
                .andRespond(withSuccess("{\"replayed\":false}", MediaType.APPLICATION_JSON));

        client.setPersonalization(USER_ID, new ObjectMapper().createObjectNode(), null);

        server.verify();
    }

    @Test
    @DisplayName("baseUrl이 비어 있으면 호출하지 않고 INTERNAL_ERROR — 대체할 값이 없다")
    void failsFastWithoutBaseUrl() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer unused = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        ProfileGraphClient offline = new ProfileGraphClient(restClient, restClient,
                new LlmProperties("", null), new ObjectMapper(), TOKEN);

        assertThatThrownBy(() -> offline.getGraph(USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR);

        unused.verify(); // 기대한 요청이 없다 = 호출 자체를 안 했다
    }
}
