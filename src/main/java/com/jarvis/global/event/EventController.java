package com.jarvis.global.event;

import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.auth.ClientIp;
import com.jarvis.global.auth.GuestCookieManager;
import com.jarvis.global.event.dto.EventBatchRequest;
import com.jarvis.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** E-1 (04 §8) — 인증 선택(permitAll + JWT 있으면 주입), 202 즉시 응답 */
@RestController
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;
    private final GuestCookieManager guestCookieManager;

    @PostMapping("/api/events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Void> collect(@Valid @RequestBody EventBatchRequest request,
                                     @AuthenticationPrincipal AuthUser authUser,
                                     HttpServletRequest httpRequest) {
        eventService.collect(request,
                authUser == null ? null : authUser.memberId(),
                guestCookieManager.resolve(httpRequest).orElse(null),
                ClientIp.resolve(httpRequest));
        return ApiResponse.success(null);
    }
}
