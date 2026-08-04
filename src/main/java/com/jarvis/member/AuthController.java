package com.jarvis.member;

import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.auth.AccessCookieManager;
import com.jarvis.global.auth.ClientIp;
import com.jarvis.global.auth.GuestCookieManager;
import com.jarvis.global.auth.RefreshCookieManager;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.member.dto.AuthResult;
import com.jarvis.member.dto.LoginRequest;
import com.jarvis.member.dto.LoginResponse;
import com.jarvis.member.dto.MeResponse;
import com.jarvis.member.dto.SignupRequest;
import com.jarvis.member.dto.SignupResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-1~A-5 (04 §1). RT·guest_id 쿠키 읽기/쓰기만 여기서 — 나머지는 전부 서비스 (03 §3-1).
 * 게스트 승계 신원은 guest_id HttpOnly 쿠키에서 서버가 직접 취한다(노션 A-1/A-2 2026-07-20 개정
 * — E-1과 동일 원칙: 신원은 서버 주입, body의 신원 주장은 무시).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshCookieManager refreshCookieManager;
    private final GuestCookieManager guestCookieManager;
    private final ClientIp clientIp;
    private final AccessCookieManager accessCookieManager;

    @PostMapping("/signup")
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        String guestId = guestCookieManager.resolve(httpRequest).orElse(null);
        AuthResult result = authService.signup(request, clientIp.resolve(httpRequest), guestId);
        writeTokens(httpResponse, result);
        retireGuest(httpResponse, guestId);
        return ApiResponse.success(new SignupResponse(result.member()));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        String guestId = guestCookieManager.resolve(httpRequest).orElse(null);
        AuthResult result = authService.login(request, clientIp.resolve(httpRequest), guestId);
        writeTokens(httpResponse, result);
        retireGuest(httpResponse, guestId);
        return ApiResponse.success(new LoginResponse(result.member()));
    }

    /** AT·RT를 한 자리에서 내보낸다 — 한쪽만 갱신되는 실수를 막는다 */
    private void writeTokens(HttpServletResponse httpResponse, AuthResult result) {
        accessCookieManager.write(httpResponse, result.accessToken());
        refreshCookieManager.write(httpResponse, result.refreshToken());
    }

    /**
     * 승계가 끝났으면 guest_id 쿠키를 반납한다 — 그 익명 구간은 끝났고, 이후 로그아웃 뒤의 익명 활동은
     * 새 게스트로 쌓인다(GUEST-LIFECYCLE). 실패 경로는 예외로 빠지므로 여기까지 오지 않는다.
     */
    private void retireGuest(HttpServletResponse httpResponse, String guestId) {
        if (guestId != null) {
            guestCookieManager.clear(httpResponse);
        }
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authService.logout(refreshCookieManager.resolve(httpRequest).orElse(null),
                clientIp.resolve(httpRequest));
        accessCookieManager.expire(httpResponse);
        refreshCookieManager.expire(httpResponse);
        return ApiResponse.success(null);
    }

    @PostMapping("/refresh")
    public ApiResponse<Void> refresh(HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) {
        AuthResult result = authService.refresh(refreshCookieManager.resolve(httpRequest).orElse(null));
        writeTokens(httpResponse, result);
        return ApiResponse.success(null);
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(authService.me(authUser.memberId()));
    }
}
