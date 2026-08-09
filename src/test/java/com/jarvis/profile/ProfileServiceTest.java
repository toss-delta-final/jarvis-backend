package com.jarvis.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.recommendation.HomeRecommendationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** M-11~M-16 (노션) — 캐시 무효화(C-27)와 If-Match 게이트가 이 서비스의 전부다 */
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

    private static final long MEMBER_ID = 7L;
    private static final String EDGE_ID = "e_2f80d1aa63b74c19";
    private static final String IF_MATCH = "\"g42\"";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock ProfileGraphClient profileGraphClient;
    @Mock HomeRecommendationService homeRecommendationService;

    @InjectMocks ProfileService service;

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("M-11 조회는 캐시를 건드리지 않는다 — 읽기라 버릴 이유가 없다")
    void getGraphDoesNotEvict() {
        when(profileGraphClient.getGraph(MEMBER_ID)).thenReturn(json("{\"exists\":true}"));

        service.getGraph(MEMBER_ID);

        verifyNoInteractions(homeRecommendationService);
    }

    @Test
    @DisplayName("M-13 삭제 성공 시 P-5 캐시를 버린다 — 안 버리면 지운 취향이 최대 10분간 홈에 남는다")
    void deleteEdgeEvictsCache() {
        when(profileGraphClient.deleteEdge(MEMBER_ID, EDGE_ID, IF_MATCH))
                .thenReturn(json("{\"replayed\":false}"));

        service.deleteEdge(MEMBER_ID, EDGE_ID, IF_MATCH);

        verify(homeRecommendationService).evictCache(MEMBER_ID);
    }

    @Test
    @DisplayName("M-12 수정 성공 시에도 캐시를 버린다 — 고치기 전 취향으로 만든 추천이 남으면 안 된다")
    void updateEdgeEvictsCache() {
        when(profileGraphClient.updateEdge(eq(MEMBER_ID), eq(EDGE_ID), any(), eq(IF_MATCH)))
                .thenReturn(json("{\"merged\":false}"));

        service.updateEdge(MEMBER_ID, EDGE_ID, json("{\"predicate\":\"avoids\"}"), IF_MATCH);

        verify(homeRecommendationService).evictCache(MEMBER_ID);
    }

    @Test
    @DisplayName("M-15 초기화 성공 시에도 캐시를 버린다")
    void resetEvictsCache() {
        when(profileGraphClient.reset(eq(MEMBER_ID), any(), eq(IF_MATCH)))
                .thenReturn(json("{\"purged\":{}}"));

        service.reset(MEMBER_ID, json("{\"scope\":\"ALL\"}"), IF_MATCH);

        verify(homeRecommendationService).evictCache(MEMBER_ID);
    }

    @Test
    @DisplayName("M-16은 If-Match 없이도 실행된다 — 프라이버시 스위치가 충돌로 잠기면 안 된다")
    void personalizationAllowsMissingIfMatch() {
        when(profileGraphClient.setPersonalization(eq(MEMBER_ID), any(), eq(null)))
                .thenReturn(json("{\"personalization\":{\"enabled\":false}}"));

        service.setPersonalization(MEMBER_ID, json("{\"enabled\":false}"), null);

        verify(homeRecommendationService).evictCache(MEMBER_ID);
    }

    @Test
    @DisplayName("AI 호출이 실패하면 캐시를 버리지 않는다 — 상태가 그대로라 버릴 이유가 없다")
    void doesNotEvictWhenClientFails() {
        when(profileGraphClient.deleteEdge(MEMBER_ID, EDGE_ID, IF_MATCH))
                .thenThrow(new BusinessException(ErrorCode.PROFILE_VERSION_CONFLICT));

        assertThatThrownBy(() -> service.deleteEdge(MEMBER_ID, EDGE_ID, IF_MATCH))
                .isInstanceOf(BusinessException.class);

        verify(homeRecommendationService, never()).evictCache(anyLong());
    }

    @ParameterizedTest(name = "If-Match = [{0}]")
    @ValueSource(strings = {"", "   ", "*", "W/\"g42\""})
    @DisplayName("누락·빈 값·*·약한 태그는 400 — 실행하지 않고 AI를 부르지도 않는다")
    void rejectsBadIfMatch(String ifMatch) {
        assertThatThrownBy(() -> service.deleteEdge(MEMBER_ID, EDGE_ID, ifMatch))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        verifyNoInteractions(profileGraphClient, homeRecommendationService);
    }

    @Test
    @DisplayName("If-Match 누락 시 M-12·M-15도 같이 막힌다")
    void requiresIfMatchOnWrites() {
        assertThatThrownBy(() -> service.updateEdge(MEMBER_ID, EDGE_ID, json("{}"), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.reset(MEMBER_ID, json("{\"scope\":\"ALL\"}"), null))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(profileGraphClient);
    }

    @Test
    @DisplayName("M-16도 If-Match를 보냈다면 형식은 본다 — *는 400")
    void validatesIfMatchFormatWhenPresent() {
        assertThatThrownBy(() -> service.setPersonalization(MEMBER_ID, json("{}"), "*"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    @Test
    @DisplayName("If-Match 값은 변형하지 않고 그대로 넘긴다 — 재따옴표 금지(C-21)")
    void passesIfMatchVerbatim() {
        when(profileGraphClient.deleteEdge(anyLong(), anyString(), eq("g42")))
                .thenReturn(json("{}"));

        service.deleteEdge(MEMBER_ID, EDGE_ID, "g42");

        verify(profileGraphClient).deleteEdge(MEMBER_ID, EDGE_ID, "g42");
    }

    @Test
    @DisplayName("응답 본문은 해석하지 않고 그대로 돌려준다 — 모르는 필드도 살아남아야 한다")
    void returnsBodyVerbatim() {
        JsonNode aiBody = json("""
                {"userId":123,"graphVersion":"g42","exists":true,
                 "edges":[{"edgeId":"e_1","object":{"label":"소니"}}],
                 "계약에없는필드":"살아있어야 한다"}""");
        when(profileGraphClient.getGraph(MEMBER_ID)).thenReturn(aiBody);

        JsonNode result = service.getGraph(MEMBER_ID);

        assertThat(result).isSameAs(aiBody);
        assertThat(result.path("계약에없는필드").asText()).isEqualTo("살아있어야 한다");
    }
}
