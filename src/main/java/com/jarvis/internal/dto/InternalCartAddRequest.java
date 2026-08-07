package com.jarvis.internal.dto;

import com.jarvis.recommendation.dto.RecommendationContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * I-2 요청 (05 §I-2) — userId/guestId는 채팅 티켓의 메아리, 둘 중 하나 필수. 검증은 C-2와 동일.
 *
 * <p>{@code recommendationContext}는 FastAPI가 싣는다(선택). 챗봇이 직전 추천에서 "담아줘"를 처리하는
 * 경로라 추천→전환의 가장 직접적인 증거다(노션 I-2).
 *
 * <p>{@code chatSessionId}(2026-08-06 추가) — FastAPI가 자기 채팅 sessionId를 싣는다. FastAPI는 FE
 * localStorage의 세션 키를 알 수 없으므로 Spring이 {@code "chat:"+chatSessionId}로 sentinel을 조립해
 * 이벤트에 적재한다(노션 I-2 「C안 확정」). 없으면 담기는 성공하고 이벤트만 건너뛴다.
 */
public record InternalCartAddRequest(Long userId, String guestId,
                                     @NotNull Long productId, Long optionId,
                                     @NotNull @Min(1) @Max(99) Integer quantity,
                                     @Valid RecommendationContext recommendationContext,
                                     String chatSessionId) {

    @AssertTrue(message = "userId 또는 guestId 중 하나가 필요합니다")
    public boolean isIdentityPresent() {
        return userId != null || (guestId != null && !guestId.isBlank());
    }
}
