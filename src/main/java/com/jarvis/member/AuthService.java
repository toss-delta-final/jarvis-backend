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

    /** A-1 — 가입 + 자동 로그인 + 게스트 승계 (04) */
    @Transactional
    public AuthResult signup(SignupRequest request, String clientIp) {
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
        convertGuest(member.getId(), request.guestId());
        accountEventLogger.log(member.getId(), AccountEventType.SIGNUP, clientIp);
        return issueTokens(member);
    }

    /** A-2 — 실패는 계정 존재 여부 무관 통일 401 AUTH_LOGIN_FAILED (04) */
    @Transactional
    public AuthResult login(LoginRequest request, String clientIp) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> loginFail(null, clientIp));
        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw loginFail(member.getId(), clientIp);
        }
        convertGuest(member.getId(), request.guestId());
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
                    // 채팅 세션 정리 + I-20 통지 (05 §2-1 — 트리거: 로그아웃)
                    chatSessionService.endSession(
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
     * 게스트 승계 (02 D5·D30) — converted_member_id 기록 + behavior_events member_id 백필 + 장바구니 병합.
     * 백필·convertTo는 최초 1회지만, 장바구니 병합은 재로그인에도 수행 —
     * 로그아웃 후 같은 쿠키로 담은 게스트분이 다음 로그인에 따라오도록 (02 D30 "그대로 따라온다").
     */
    private void convertGuest(Long memberId, String guestId) {
        if (guestId == null || guestId.isBlank()) {
            return;
        }
        guestRepository.findById(guestId)
                .ifPresent(guest -> {
                    if (!guest.isConverted()) {
                        guest.convertTo(memberId);
                        jdbcTemplate.update(
                                "UPDATE behavior_events SET member_id = ? WHERE guest_id = ? AND member_id IS NULL",
                                memberId, guestId);
                    }
                    cartService.mergeGuestCart(memberId, guestId);
                });
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
