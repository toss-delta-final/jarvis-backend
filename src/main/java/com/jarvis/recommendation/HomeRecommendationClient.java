package com.jarvis.recommendation;

import com.jarvis.internal.InternalTokenFilter;
import com.jarvis.chat.LlmProperties;
import com.jarvis.recommendation.dto.HomeRecommendationRequest;
import com.jarvis.recommendation.dto.HomeRecommendationResponse;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * I-22 홈 개인화 추천 호출 (노션 I-22) — Spring → FastAPI. P-5의 유일한 발화 지점이다.
 *
 * <p><b>실패를 던지지 않는다.</b> 노션 P-5가 "FastAPI 실패는 실패 응답이 아니다"로 못 박았다 —
 * 타임아웃·5xx·프로필 없음은 전부 P-4 인기상품 대체로 200이 나가야 하므로, 여기서는 사유만
 * 돌려주고 판단은 호출자({@link HomeRecommendationService})가 한다.
 *
 * <p>예산은 {@code llmRestClient}의 연결 2s / 응답 3s를 그대로 쓴다(03 §5) — 채팅용 60s와 달리
 * 메인 화면 렌더를 막으면 안 되기 때문이다. 채팅과 같은 빈을 쓰는 건 두 예산이 마침 같아서다.
 */
@Slf4j
@Component
public class HomeRecommendationClient {

    private static final String PATH = "/internal/recommendations/home";

    private final RestClient llmRestClient;
    private final LlmProperties llmProperties;
    private final String internalToken;

    public HomeRecommendationClient(RestClient llmRestClient, LlmProperties llmProperties,
                                    @Value("${app.internal.token:}") String internalToken) {
        this.llmRestClient = llmRestClient;
        this.llmProperties = llmProperties;
        this.internalToken = internalToken;
    }

    /** 호출 결과 — 응답이 없으면 {@code fallbackReason}이 왜 없는지를 담는다 */
    public record Result(HomeRecommendationResponse response, String fallbackReason) {

        static Result of(HomeRecommendationResponse response) {
            return new Result(response, null);
        }

        static Result failed(String fallbackReason) {
            return new Result(null, fallbackReason);
        }
    }

    /** {@code fallbackReason} 어휘 (노션 P-5) — 와이어에 싣지 않고 recommendation_generated에만 저장한다 */
    public static final String PROFILE_MISSING = "PROFILE_MISSING";
    public static final String INSUFFICIENT_CANDIDATES = "INSUFFICIENT_CANDIDATES";
    public static final String AI_ERROR = "AI_ERROR";
    public static final String AI_TIMEOUT = "AI_TIMEOUT";

    public Result recommend(HomeRecommendationRequest request) {
        if (llmProperties.baseUrl() == null || llmProperties.baseUrl().isBlank()) {
            // FastAPI 미기동(로컬) — 홈이 죽으면 안 되므로 인기상품으로 간다.
            // 조용히 빠지면 배포 환경에서 이 설정 누락이 "개인화가 영영 안 됨"으로만 보이고
            // 호출 자체를 안 했으니 아래 catch의 warn도 안 찍힌다 — 그래서 여기서 남긴다
            log.warn("I-22 미호출 — app.llm.base-url이 비어 있다. 홈 추천은 전량 인기상품 대체다 "
                    + "(memberId={})", request.memberId());
            return Result.failed(AI_ERROR);
        }
        try {
            HomeRecommendationResponse response = llmRestClient.post()
                    .uri(llmProperties.baseUrl() + PATH)
                    .header(InternalTokenFilter.HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(HomeRecommendationResponse.class);
            return Optional.ofNullable(response).map(Result::of)
                    .orElseGet(() -> Result.failed(AI_ERROR));
        } catch (RestClientException e) {
            // 타임아웃과 그 외를 가르는 건 관측용이다 — 둘 다 대체 동작은 같다
            String reason = isTimeout(e) ? AI_TIMEOUT : AI_ERROR;
            log.warn("I-22 홈 추천 실패 — {}로 대체 (memberId={}): {}",
                    reason, request.memberId(), e.getMessage());
            return Result.failed(reason);
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
