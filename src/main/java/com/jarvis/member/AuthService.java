package com.jarvis.member;

import com.jarvis.cart.CartService;
import com.jarvis.chat.ChatIdentity;
import com.jarvis.chat.ChatSessionService;
import com.jarvis.chat.SessionEndReason;
import com.jarvis.global.auth.JwtProperties;
import com.jarvis.global.auth.JwtProvider;
import com.jarvis.global.auth.TokenHasher;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.member.dto.AuthResult;
import com.jarvis.member.dto.LoginRequest;
import com.jarvis.member.dto.MeResponse;
import com.jarvis.member.dto.SignupRequest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MemberRepository memberRepository;
    private final GuestRepository guestRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccountEventLogger accountEventLogger;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final JdbcTemplate jdbcTemplate;
    private final CartService cartService;
    private final ChatSessionService chatSessionService;

    /** A-1 — 가입 + 자동 로그인 + 게스트 승계(백필 + 병합 — 가입 전용). guestId는 쿠키 유래(컨트롤러 주입) */
    @Transactional
    public AuthResult signup(SignupRequest request, String clientIp, String guestId) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new BusinessException(ErrorCode.MEMBER_EMAIL_DUPLICATE);
        }
        Member member = memberRepository.save(Member.signup(
                request.email(),
                passwordEncoder.encode(request.password()),
                request.nickname(),
                request.gender(),
                request.birthDate(),
                LocalDateTime.now()));
        inheritGuest(member.getId(), guestId, true);
        accountEventLogger.log(member.getId(), AccountEventType.SIGNUP, clientIp);
        return issueTokens(member);
    }

    /** A-2 — 실패는 계정 존재 여부 무관 통일 401 AUTH_LOGIN_FAILED (04). 승계는 장바구니 병합만 */
    @Transactional
    public AuthResult login(LoginRequest request, String clientIp, String guestId) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> loginFail(null, clientIp));
        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw loginFail(member.getId(), clientIp);
        }
        inheritGuest(member.getId(), guestId, false);
        accountEventLogger.log(member.getId(), AccountEventType.LOGIN_SUCCESS, clientIp);
        return issueTokens(member);
    }

    /** A-4 — RT 검증 후 AT 재발급 + RT 교체 (02 D6). 무효/만료 RT는 재로그인 유도(AUTH_REQUIRED) */
    @Transactional
    public AuthResult refresh(String refreshToken) {
        if (refreshToken == null) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        String tokenHash = TokenHasher.sha256Hex(refreshToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
        if (stored.isExpired(LocalDateTime.now())) {
            refreshTokenRepository.deleteByTokenHash(tokenHash);
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        // 회전 단일성: 조건부 삭제가 정확히 1건일 때만 재발급 — 동시 refresh는 한쪽만 성공, 나머지는 재로그인 유도 (02 D6)
        if (refreshTokenRepository.deleteByTokenHash(tokenHash) != 1) {
            throw new BusinessException(ErrorCode.AUTH_REQUIRED);
        }
        Member member = memberRepository.findById(stored.getMemberId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AUTH_REQUIRED));
        return issueTokens(member);
    }

    /** A-3 — RT 쿠키 기준, 없어도 성공 (04 — AT 만료 상태에서도 로그아웃 가능) */
    @Transactional
    public void logout(String refreshToken, String clientIp) {
        if (refreshToken == null) {
            return;
        }
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(refreshToken))
                .ifPresent(stored -> {
                    refreshTokenRepository.delete(stored);
                    accountEventLogger.log(stored.getMemberId(), AccountEventType.LOGOUT, clientIp);
                    // 채팅 세션 정리 + I-20 통지 (05 §2-1 — 트리거: 로그아웃).
                    // 비동기 분리: 열린 DB 트랜잭션이 Redis 응답을 기다리며 커넥션·락을 쥐고 있지 않게.
                    // Redis 장애여도 로그아웃은 성공(실패는 async 쪽 warn, 세션은 TTL로 소멸)
                    chatSessionService.endSessionAsync(
                            ChatIdentity.member(stored.getMemberId()), SessionEndReason.LOGOUT);
                });
    }

    /** A-5 */
    public MeResponse me(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return MeResponse.from(member);
    }

    private BusinessException loginFail(Long memberId, String clientIp) {
        accountEventLogger.log(memberId, AccountEventType.LOGIN_FAIL, clientIp);
        return new BusinessException(ErrorCode.AUTH_LOGIN_FAILED);
    }

    /**
     * 게스트 승계 (02 D5·D30, 노션 A-1/A-2 2026-07-20 개정) —
     * 가입(A-1): converted_member_id 기록 + behavior_events member_id 백필 + 장바구니 병합.
     * 로그인(A-2): 장바구니 병합만 — 공용 PC에서 앞 사람의 탐색 이력이 다른 계정에 귀속되면
     * 추천·분석이 오염되므로 백필은 가입 전용. 병합은 재로그인에도 매번 수행(02 D30).
     * 승계 후 해당 게스트의 채팅 세션 종료 + I-20 NEW_CONVERSATION 통지(FastAPI 맥락 초기화)
     * — 이후 채팅은 CH-1로 새 세션.
     */
    private void inheritGuest(Long memberId, String guestId, boolean backfill) {
        if (guestId == null || guestId.isBlank()) {
            return;
        }
        guestRepository.findById(guestId)
                .ifPresent(guest -> {
                    if (backfill && !guest.isConverted()) {
                        guest.convertTo(memberId);
                        jdbcTemplate.update(
                                "UPDATE behavior_events SET member_id = ? WHERE guest_id = ? AND member_id IS NULL",
                                memberId, guestId);
                    }
                    cartService.mergeGuestCart(memberId, guestId);
                });
        chatSessionService.endSessionAsync(ChatIdentity.guest(guestId), SessionEndReason.NEW_CONVERSATION);
    }

    private AuthResult issueTokens(Member member) {
        String accessToken = jwtProvider.createAccessToken(member.getId(), member.getRole());
        String refreshToken = generateRefreshToken();
        refreshTokenRepository.save(RefreshToken.issue(
                member.getId(),
                TokenHasher.sha256Hex(refreshToken),
                LocalDateTime.now().plusDays(jwtProperties.refreshTokenDays())));
        return new AuthResult(accessToken, refreshToken, MeResponse.from(member));
    }

    private String generateRefreshToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
