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

    /**
     * CH-1 — 멱등 발급(노션 CH-1 2026-07-31 개정): 같은 신원·채널의 활성 세션이 있으면 그대로 돌려준다.
     * "새 대화"는 FE가 threadId만 갱신하므로 더 이상 이 API를 부르지 않는다.
     * Body 없음·channel 미지정은 SHOPPING 기본값, SELLER 채널은 S-4 별도 입구 전용
     */
    @PostMapping("/sessions")
    public ApiResponse<ChatSessionResponse> createSession(@RequestBody(required = false) ChatSessionRequest request,
                                                          @AuthenticationPrincipal AuthUser authUser,
                                                          HttpServletRequest httpRequest,
                                                          HttpServletResponse httpResponse) {
        ChatChannel channel = request == null || request.channel() == null
                ? ChatChannel.SHOPPING : request.channel();
        if (channel == ChatChannel.SELLER) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR);
        }
        ChatIdentity identity = authUser != null
                ? ChatIdentity.member(authUser.memberId())
                : guestIdentity(httpRequest, httpResponse);
        return ApiResponse.success(chatSessionService.issueSession(identity, channel));
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

    /**
     * CH-7 (이슈 #63) — 채팅 화면에서 로그인·가입했을 때 FE가 호출해 게스트 접속을 회원으로 승계한다.
     * 자동이 아닌 이유: 모든 로그인에 대화를 귀속시키지 않고, AI 동기 호출을 로그인 트랜잭션에 넣지
     * 않기 위해서다. 신원은 AT(회원)와 세션에 기록된 게스트의 귀속 기록으로만 판정한다 — 클라이언트가
     * guestId를 주장하지 않는다.
     */
    @PostMapping("/sessions/{sessionId}/claim")
    public ApiResponse<ChatSessionResponse> claimSession(@PathVariable String sessionId,
                                                         @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(chatSessionService.claimSession(authUser.memberId(), sessionId));
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

    /**
     * CH-5 — FE가 SSE products.ready{listIds[]} 수신 후 목록마다 호출 (05 §1-2-1).
     * 신원 해석은 CH-1b와 동일(회원 AT 우선, 없으면 guest_id 쿠키) — listId만으로는 못 읽는다(노션 CH-5)
     */
    @GetMapping("/lists/{listId}")
    public ApiResponse<RecommendationListResponse> getList(@PathVariable String listId,
                                                           @AuthenticationPrincipal AuthUser authUser,
                                                           HttpServletRequest httpRequest) {
        ChatIdentity identity = authUser != null
                ? ChatIdentity.member(authUser.memberId())
                : guestCookieManager.resolve(httpRequest).map(ChatIdentity::guest).orElse(null);
        return ApiResponse.success(recommendationListService.getList(listId, identity));
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
