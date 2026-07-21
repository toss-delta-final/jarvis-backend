package com.jarvis.global.event;

import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.auth.ClientIp;
import com.jarvis.global.auth.GuestCookieManager;
import com.jarvis.global.event.dto.EventBatchRequest;
import com.jarvis.member.GuestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** E-1 (04 §8) — 인증 선택(permitAll + JWT 있으면 주입), 202 즉시 응답(본문 없음 — 노션 명세) */
@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final GuestCookieManager guestCookieManager;
    private final GuestService guestService;

    @PostMapping("/api/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void collect(@Valid @RequestBody EventBatchRequest request,
                        @AuthenticationPrincipal AuthUser authUser,
                        HttpServletRequest httpRequest,
                        HttpServletResponse httpResponse) {
        Long memberId = authUser == null ? null : authUser.memberId();
        String guestId = guestCookieManager.resolve(httpRequest).orElse(null);
        // 익명 요청인데 쿠키가 없으면 여기서 게스트 발급(노션 E-1 2026-07-20 변경) — 종전엔 첫 담기·
        // 첫 채팅에서만 발급돼 session_start/page_view 등이 주체 없는 행(member_id·guest_id 둘 다
        // NULL)으로 쌓였고 가입 백필 대상도 아니었다(I-13 uniqueVisitors·viewToCartRate 왜곡 원인)
        if (memberId == null) {
            String issued = guestService.ensureGuest(guestId);
            if (issued != null) {
                guestCookieManager.write(httpResponse, issued);
                guestId = issued;
            }
        }
        eventService.collect(request, memberId, guestId, ClientIp.resolve(httpRequest));
    }
}
