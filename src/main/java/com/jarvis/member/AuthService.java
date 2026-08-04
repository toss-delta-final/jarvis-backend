package com.jarvis.member;

import com.jarvis.cart.CartService;
import com.jarvis.chat.ChatIdentity;
import com.jarvis.chat.ChatSessionService;
import com.jarvis.chat.SessionEndReason;
import com.jarvis.global.auth.JwtProperties;
import com.jarvis.global.auth.JwtProvider;
import com.jarvis.global.auth.TokenEpoch;
import com.jarvis.global.auth.TokenHasher;
import com.jarvis.global.ratelimit.LoginRateLimiter;
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
    private final CartService cartService;
    private final ChatSessionService chatSessionService;
    private final LoginRateLimiter loginRateLimiter;
    private final TokenEpoch tokenEpoch;

    /** A-1 — 가입 + 자동 로그인 + 게스트 구간 승계. guestId는 쿠키 유래(컨트롤러 주입) */
    @Transactional
    public AuthResult signup(SignupRequest request, String clientIp, String guestId) {
        // 무차별 계정 생성 차단 (07 §3-4). 로그인과 같은 카운터를 쓴다 — 두 경로 모두 이메일을 축으로
        // 삼고, 공격자가 한쪽 한도를 채운 뒤 다른 쪽으로 넘어가는 걸 막는다.
        loginRateLimiter.check(clientIp, request.email());
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
        inheritGuest(member.getId(), guestId);
        accountEventLogger.log(member.getId(), AccountEventType.SIGNUP, clientIp);
        return issueTokens(member);
    }

    /** A-2 — 실패는 계정 존재 여부 무관 통일 401 AUTH_LOGIN_FAILED (04). 승계는 가입과 동일 */
    @Transactional
    public AuthResult login(LoginRequest request, String clientIp, String guestId) {
        // 비밀번호 대조 전에 센다 — 존재하지 않는 계정으로 찌르는 것도 시도로 잡아야 한다
        loginRateLimiter.check(clientIp, request.email());
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> loginFail(null, clientIp));
        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw loginFail(member.getId(), clientIp);
        }
        inheritGuest(member.getId(), guestId);
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
                    // RT를 지워도 이미 나가 있는 AT는 수명(30분)까지 살아 있다 — 탈취본이 있다면
                    // 그동안 그대로 통한다. 무효화 마커로 즉시 끊는다(07 §3-2).
                    tokenEpoch.invalidate(stored.getMemberId());
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
     * 게스트 구간 승계 (GUEST-LIFECYCLE · 노션 A-1/A-2 2026-07-31 개정) — 가입·로그인이 완전히 동일하다.
     * ① 장바구니 병합(사용자가 보고 쓰던 상태라 즉시 옮긴다, 02 D30) ② 구간 귀속 기록 ③ 채팅 세션 정리.
     *
     * <p>behavior_events 백필은 폐기했다 — 로그는 "일어난 사실"이라 고쳐 쓰지 않고, "이 구간의 주인은
     * 회원 N"이라는 귀속 기록으로 해석한다(CH-5 소유자 예외 · I-16 귀속 조인).
     * 쿠키 반납은 컨트롤러 소관이다(03 §3-1 — 쿠키 읽기/쓰기는 컨트롤러에서만).
     *
     * <p><b>게스트 채팅 세션은 건드리지 않는다</b> — 여기서 지우면 뒤이어 오는 CH-7이 승계할 대상을
     * 잃는다(이슈 #63). 채팅 화면에서 시작한 로그인이면 FE가 CH-7로 승계하고, 아니면 그 세션은
     * 주인 없이 남아 TTL(10분)로 소멸한다. 쿠키가 반납돼 신원이 사라졌으므로 CH-1b로 티켓을
     * 갱신할 수도 없어, 남아 있어도 접근 경로가 없다.
     */
    private void inheritGuest(Long memberId, String guestId) {
        if (guestId == null || guestId.isBlank()) {
            return;
        }
        guestRepository.findById(guestId)
                .ifPresent(guest -> {
                    guest.convertTo(memberId);
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
