package com.jarvis.chat;

import com.jarvis.brand.Brand;
import com.jarvis.chat.dto.ChatSessionRequest;
import com.jarvis.chat.dto.ChatSessionResponse;
import com.jarvis.chat.dto.RecommendationListResponse;
import com.jarvis.chat.dto.TicketReissueRequest;
import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.auth.GuestCookieManager;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.GuestService;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.seller.SellerBrandResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CH-1/CH-1b/CH-5 (04 §6) — 채팅 SSE는 FastAPI 직결(03 D5)이라 Spring의 역할은
 * 세션 + 단명 스트림 티켓 발급과 추천 목록 조회뿐. 게스트도 동일 경로(쿠키 없으면 발급).
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatSessionService chatSessionService;
    private final RecommendationListService recommendationListService;
    private final GuestCookieManager guestCookieManager;
    private final GuestService guestService;
    private final SellerBrandResolver sellerBrandResolver;

    /** CH-1 — "새 대화" 버튼도 이걸 재호출 (05 §1-0). SELLER 채널은 S-4 별도 입구 전용 */
    @PostMapping("/sessions")
    public ApiResponse<ChatSessionResponse> createSession(@Valid @RequestBody ChatSessionRequest request,
                                                          @AuthenticationPrincipal AuthUser authUser,
                                                          HttpServletRequest httpRequest,
                                                          HttpServletResponse httpResponse) {
        if (request.channel() == ChatChannel.SELLER) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        ChatIdentity identity = authUser != null
                ? ChatIdentity.member(authUser.memberId())
                : guestIdentity(httpRequest, httpResponse);
        return ApiResponse.success(chatSessionService.issueSession(identity, request.channel()));
    }

    /**
     * S-4 (04 §7) — 판매자 챗봇 세션 + SELLER 스코프 티켓. /api/chat/seller/**는 ROLE_SELLER 가드,
     * brandId는 JWT 검증 후 DB에서 도출해 티켓 claim에 박는다(클라이언트/LLM 주장 무시).
     */
    @PostMapping("/seller/sessions")
    public ApiResponse<ChatSessionResponse> createSellerSession(@AuthenticationPrincipal AuthUser authUser) {
        Brand brand = sellerBrandResolver.resolve(authUser.memberId());
        return ApiResponse.success(chatSessionService.issueSellerSession(
                ChatIdentity.member(authUser.memberId()), brand.getId()));
    }

    /** CH-1b — 티켓 만료 401 시 FE가 호출 후 1회 재시도 (05 §1-0) */
    @PostMapping("/tickets")
    public ApiResponse<ChatSessionResponse> reissueTicket(@Valid @RequestBody TicketReissueRequest request,
                                                          @AuthenticationPrincipal AuthUser authUser,
                                                          HttpServletRequest httpRequest) {
        ChatIdentity identity = authUser != null
                ? ChatIdentity.member(authUser.memberId())
                : guestCookieManager.resolve(httpRequest).map(ChatIdentity::guest).orElse(null);
        return ApiResponse.success(chatSessionService.reissueTicket(identity, request.sessionId()));
    }

    /** CH-5 — FE가 SSE products.ready{listId} 수신 후 호출 (05 §1-2-1) */
    @GetMapping("/lists/{listId}")
    public ApiResponse<RecommendationListResponse> getList(@PathVariable String listId) {
        return ApiResponse.success(recommendationListService.getList(listId));
    }

    /** 게스트 신원 — 쿠키·행 없으면 발급이 곧 신원 확정 (03 D3 "발급 = 쿠키 세팅 + 행 INSERT") */
    private ChatIdentity guestIdentity(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String guestId = guestCookieManager.resolve(httpRequest).orElse(null);
        String issued = guestService.ensureGuest(guestId);
        if (issued != null) {
            guestCookieManager.write(httpResponse, issued);
            guestId = issued;
        }
        return ChatIdentity.guest(guestId);
    }
}
