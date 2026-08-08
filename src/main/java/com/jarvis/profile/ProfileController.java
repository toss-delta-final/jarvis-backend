package com.jarvis.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.jarvis.global.auth.AuthUser;
import com.jarvis.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 취향 프로필 M-11~M-16 (04 §5) — I-32~I-37 프록시. 🔑 전 경로 USER 전용.
 *
 * <p><b>회원 id를 요청에서 받지 않는다.</b> 경로·쿼리·본문 어디에도 없고 로그인 세션에서만 뽑는다 —
 * FE가 보낸 값을 믿으면 남의 프로필을 열 수 있다(IDOR). E-1·A-1의 "신원은 서버 주입" 원칙과 같다.
 *
 * <p>{@code If-Match}는 필수 여부가 API마다 달라(M-16만 선택) 검증을 서비스에 둔다.
 * 여기서는 헤더를 있는 그대로 넘기기만 한다 — 재따옴표·정규화 금지(C-21).
 */
@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    /** M-11 — 프로필이 없어도 404가 아니다. AI가 {@code exists:false}로 200을 준다 */
    @GetMapping("/graph")
    public ApiResponse<JsonNode> graph(@AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(profileService.getGraph(authUser.memberId()));
    }

    /** M-12 — 응답의 edgeId가 요청 값과 다를 수 있다(식별자가 관계·대상 파생이라 수정하면 새 값) */
    @PatchMapping("/graph/edges/{edgeId}")
    public ApiResponse<JsonNode> updateEdge(
            @PathVariable String edgeId,
            @RequestBody JsonNode body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(
                profileService.updateEdge(authUser.memberId(), edgeId, body, ifMatch));
    }

    /** M-13 — 본문 없음. 신원·대상이 전부 경로에 있다(M-6 찜 해제 선례) */
    @DeleteMapping("/graph/edges/{edgeId}")
    public ApiResponse<JsonNode> deleteEdge(
            @PathVariable String edgeId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(
                profileService.deleteEdge(authUser.memberId(), edgeId, ifMatch));
    }

    /** M-15 — {@code scope} 검증은 AI 몫이다. 본문을 해석하지 않고 그대로 넘긴다 */
    @PostMapping("/graph/reset")
    public ApiResponse<JsonNode> reset(
            @RequestBody JsonNode body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(profileService.reset(authUser.memberId(), body, ifMatch));
    }

    /** M-16 — {@code If-Match}가 선택인 유일한 API. 프라이버시 스위치는 충돌로 잠기면 안 된다 */
    @PutMapping("/personalization")
    public ApiResponse<JsonNode> setPersonalization(
            @RequestBody JsonNode body,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal AuthUser authUser) {
        return ApiResponse.success(
                profileService.setPersonalization(authUser.memberId(), body, ifMatch));
    }
}
