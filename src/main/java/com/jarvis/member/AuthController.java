package com.jarvis.member;

import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.auth.ClientIp;
import com.jarvis.global.auth.RefreshCookieManager;
import com.jarvis.global.response.ApiResponse;
import com.jarvis.member.dto.AuthResult;
import com.jarvis.member.dto.LoginRequest;
import com.jarvis.member.dto.LoginResponse;
import com.jarvis.member.dto.MeResponse;
import com.jarvis.member.dto.RefreshResponse;
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

/** A-1~A-5 (04 §1). RT 쿠키 읽기/쓰기만 여기서 — 나머지는 전부 서비스 (03 §3-1) */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final RefreshCookieManager refreshCookieManager;

    @PostMapping("/signup")
    public ApiResponse<SignupResponse> signup(@Valid @RequestBody SignupRequest request,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        AuthResult result = authService.signup(request, ClientIp.resolve(httpRequest));
        refreshCookieManager.write(httpResponse, result.refreshToken());
        return ApiResponse.success(new SignupResponse(result.accessToken(), result.member()));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        AuthResult result = authService.login(request, ClientIp.resolve(httpRequest));
        refreshCookieManager.write(httpResponse, result.refreshToken());
        return ApiResponse.success(new LoginResponse(result.accessToken(), result.member()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        authService.logout(refreshCookieManager.resolve(httpRequest).orElse(null),
                ClientIp.resolve(httpRequest));
        refreshCookieManager.expire(httpResponse);
        return ApiResponse.success(null);
    }

    @PostMapping("/refresh")
    public ApiResponse<RefreshResponse> refresh(HttpServletRequest httpRequest,
                                                HttpServletResponse httpResponse) {
        AuthResult result = authService.refresh(refreshCookieManager.resolve(httpRequest).orElse(null));
        refreshCookieManager.write(httpResponse, result.refreshToken());
        return ApiResponse.success(new RefreshResponse(result.accessToken()));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(authService.me(authUser.memberId()));
    }
}
