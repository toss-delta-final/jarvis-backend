package com.jarvis.chat;

import com.jarvis.chat.dto.ChatSessionResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * CH-1/CH-1b (04 §6) — 세션은 Redis TTL 10분 sliding, 대화 내용은 저장하지 않는다(03 §1).
 * CH-1은 멱등이다(노션 CH-1 2026-07-31 개정 · SPEC-CHAT-SESSION D5) — 같은 신원·채널의 활성 세션이
 * 있으면 축출하지 않고 그대로 반환한다. 방(thread)은 FE가 쥐고 있어 "새 대화"는 서버를 거치지 않으며,
 * owner 인덱스는 채널별로 두어 SHOPPING·SELLER 세션이 서로를 밀어내지 않게 한다(CS는 2026-08-11 폐기).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatSessionService {

    private static final String SESSION_KEY_PREFIX = "chat:session:";
    private static final String OWNER_KEY_PREFIX = "chat:owner:";
    private static final String DELIMITER = "|";

    private final StringRedisTemplate redisTemplate;
    private final StreamTicketProvider ticketProvider;
    private final LlmNotifyClient llmNotifyClient;
    private final ChatProperties chatProperties;
    private final LlmProperties llmProperties;
    private final com.jarvis.member.GuestService guestService;

    /** CH-1 — 세션 + 티켓 동시 발급. 같은 신원·채널의 활성 세션이 있으면 그것을 그대로 준다(D5 멱등) */
    public ChatSessionResponse issueSession(ChatIdentity identity, ChatChannel channel) {
        return issue(identity, channel, null);
    }

    /** S-4 — SELLER 세션 (04 §7). brandId를 세션 값에 보관해 CH-1b 재발급도 SELLER 티켓 유지 */
    public ChatSessionResponse issueSellerSession(ChatIdentity identity, Long brandId) {
        return issue(identity, ChatChannel.SELLER, brandId);
    }

    private ChatSessionResponse issue(ChatIdentity identity, ChatChannel channel, Long brandId) {
        String ownerKey = ownerKey(identity, channel);
        return reuseActive(ownerKey, identity)
                .orElseGet(() -> create(ownerKey, identity, channel, brandId));
    }

    /**
     * 활성 세션을 그대로 돌려주고 TTL만 민다(CH-1b와 동일 sliding). owner 키만 남고 세션이 만료된
     * 불일치는 "없음"으로 보고 새로 만들게 한다 — 두 키는 함께 갱신·삭제되므로 정상 경로엔 없다.
     */
    private Optional<ChatSessionResponse> reuseActive(String ownerKey, ChatIdentity identity) {
        String sessionId = redisTemplate.opsForValue().get(ownerKey);
        if (sessionId == null) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(sessionKey(sessionId));
        if (value == null) {
            return Optional.empty();
        }
        slide(sessionId, ownerKey);
        return Optional.of(response(sessionId, identity, brandIdOf(value)));
    }

    /**
     * owner 키를 SETNX로 잡은 쪽만 세션을 만든다 — 폰·PC 동시 접속이나 멀티탭이 각자 CH-1을 불러도
     * sessionId 하나로 수렴한다(D5). 진 쪽은 자기가 만든 세션을 버리고 이긴 세션을 받아간다.
     */
    private ChatSessionResponse create(String ownerKey, ChatIdentity identity, ChatChannel channel, Long brandId) {
        String sessionId = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(sessionKey(sessionId), sessionValue(identity, channel, brandId), sessionTtl());
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(ownerKey, sessionId, ownerTtl()))) {
            return response(sessionId, identity, brandId);
        }
        Optional<ChatSessionResponse> winner = reuseActive(ownerKey, identity);
        if (winner.isPresent()) {
            redisTemplate.delete(sessionKey(sessionId));
            return winner.get();
        }
        redisTemplate.opsForValue().set(ownerKey, sessionId, ownerTtl());
        return response(sessionId, identity, brandId);
    }

    /**
     * CH-1b — 티켓만 재발급(세션 유지·TTL sliding 연장). 발급 신원과 다르면 403 SESSION_FORBIDDEN
     * (sessionId만 알아도 남의 세션 티켓을 못 받게 — 04 CH-1b), 세션 만료·없음이면 404.
     */
    public ChatSessionResponse reissueTicket(ChatIdentity identity, String sessionId) {
        if (identity == null) {
            throw new BusinessException(ErrorCode.SESSION_FORBIDDEN);
        }
        String value = redisTemplate.opsForValue().get(sessionKey(sessionId));
        if (value == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        String[] parts = value.split("\\" + DELIMITER);
        if (!parts[0].equals(identity.subType()) || !parts[1].equals(identity.sub())) {
            throw new BusinessException(ErrorCode.SESSION_FORBIDDEN);
        }
        slide(sessionId, ownerKey(identity, ChatChannel.valueOf(parts[2])));
        return response(sessionId, identity, brandIdOf(value));
    }

    /**
     * endSession의 비동기 입구 — 열린 DB 트랜잭션 안에서 Redis를 기다리지 않도록 분리
     * (로그아웃처럼 @Transactional 안에서 호출하는 곳 전용). Redis 장애는 warn만 남기고
     * 세션은 TTL 소멸에 위임 — 호출측 흐름(RT 삭제 등)은 영향받지 않는다.
     */
    @Async
    public void endSessionAsync(ChatIdentity identity, SessionEndReason reason) {
        try {
            endSession(identity, reason);
        } catch (RuntimeException e) {
            log.warn("채팅 세션 정리 실패 — TTL 소멸에 위임. identity={}:{}, reason={}",
                    identity.subType(), identity.sub(), reason, e);
        }
    }

    /**
     * CH-7 — 게스트 접속을 회원에게 승계한다(이슈 #63). sessionId와 그 아래 모든 방(thread)은 그대로 두고
     * 주인만 바꾸므로 대화가 이어진다.
     *
     * <p><b>소유권 근거는 쿠키가 아니라 귀속 기록</b>이다 — 로그인 시점에 guest_id 쿠키가 이미 반납되므로
     * (GUEST-LIFECYCLE), "이 게스트 구간의 주인이 요청 회원인가"를 {@code guest.converted_member_id}로 묻는다.
     * 덕분에 남의 세션을 sessionId만으로 주워갈 수 없다 — 자기가 방금 은퇴시킨 게스트만 승계된다.
     *
     * <p><b>순서가 중요하다</b>: AI 전이(I-23)가 성공해야 Redis owner를 옮긴다. 뒤집으면 AI가 409로
     * 거부했을 때 "Spring은 회원, AI는 게스트"인 부분 성공이 고착된다(이슈 #63 §5).
     *
     * <p><b>멱등하다</b>(07 §3-2) — 이미 이 회원 소유인 세션이면 성공으로 돌려준다. 승계가 끝나면
     * 세션 값이 {@code member|…}가 되는데, 멱등 분기가 없으면 재시도가 아래 게스트 검사에 걸려
     * 403이 된다. 그러면 통지 성공 후 owner 이전이 실패한 부분 상태를 <b>복구할 방법이 없다</b> —
     * owner 인덱스가 게스트를 가리킨 채 남아 로그아웃이 그 세션을 못 찾는다. 재시도·더블클릭도 안전해진다.
     */
    public ChatSessionResponse claimSession(long memberId, String sessionId) {
        String value = redisTemplate.opsForValue().get(sessionKey(sessionId));
        if (value == null) {
            throw new BusinessException(ErrorCode.SESSION_NOT_FOUND);
        }
        String[] parts = value.split("\\" + DELIMITER);
        ChatChannel channel = ChatChannel.valueOf(parts[2]);
        ChatIdentity member = ChatIdentity.member(memberId);
        if (ChatIdentity.TYPE_MEMBER.equals(parts[0]) && parts[1].equals(String.valueOf(memberId))) {
            return reclaim(sessionId, member, channel, brandIdOf(value));
        }
        String guestId = parts[1];
        if (!ChatIdentity.TYPE_GUEST.equals(parts[0]) || !guestService.isOwnedBy(guestId, memberId)) {
            throw new BusinessException(ErrorCode.SESSION_FORBIDDEN);
        }
        // 다른 기기에서 이미 회원으로 대화 중이면 병합하지 않는다 — FE는 CH-1로 그 세션을 받는다(D5)
        if (redisTemplate.opsForValue().get(ownerKey(member, channel)) != null) {
            throw new BusinessException(ErrorCode.SESSION_CLAIM_CONFLICT);
        }
        llmNotifyClient.notifySessionClaim(sessionId, guestId, memberId);
        Long brandId = brandIdOf(value);
        redisTemplate.opsForValue().set(sessionKey(sessionId), sessionValue(member, channel, brandId), sessionTtl());
        redisTemplate.delete(ownerKey(ChatIdentity.guest(guestId), channel));
        redisTemplate.opsForValue().set(ownerKey(member, channel), sessionId, ownerTtl());
        return response(sessionId, member, brandId);
    }

    /**
     * 이미 승계된 세션의 재요청 — 성공으로 돌려주되 <b>끊긴 지점을 마저 세운다</b>.
     * 세션 값만 바뀌고 owner 이전에서 실패했을 수 있어 member owner를 (재)설정한다.
     *
     * <p>게스트 owner는 지우지 못한다 — 세션 값이 이미 회원이라 {@code guestId}를 알 수 없다.
     * 다만 로그인 시점에 쿠키가 반납돼 그 게스트로 올 주체가 없으므로 TTL 소멸에 맡겨도 무해하다.
     * 같은 채널에 <b>다른</b> 세션이 이미 회원 소유면 그건 승계가 아니라 충돌이다.
     */
    private ChatSessionResponse reclaim(String sessionId, ChatIdentity member,
                                        ChatChannel channel, Long brandId) {
        String owned = redisTemplate.opsForValue().get(ownerKey(member, channel));
        if (owned != null && !owned.equals(sessionId)) {
            throw new BusinessException(ErrorCode.SESSION_CLAIM_CONFLICT);
        }
        redisTemplate.opsForValue().set(ownerKey(member, channel), sessionId, ownerTtl());
        redisTemplate.expire(sessionKey(sessionId), sessionTtl());
        return response(sessionId, member, brandId);
    }

    /**
     * 세션에 기록된 신원 조회 (I-21이 추천 목록에 소유자를 박기 위해 사용 — 노션 CH-5 소유자 검증).
     * 세션 키 형식을 아는 건 이 클래스뿐이라 파싱도 여기 둔다. 만료·미존재면 empty.
     */
    public Optional<ChatIdentity> findIdentity(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        String value = redisTemplate.opsForValue().get(sessionKey(sessionId));
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\\" + DELIMITER);
        return Optional.of(new ChatIdentity(parts[0], parts[1]));
    }

    /**
     * 로그아웃 트리거 — 채널별 활성 세션을 모두 삭제 + I-20 통지 (05 §2-1).
     * Spring이 I-20을 발화하는 유일한 경로다(노션 I-20 정본 2026-07-30 — reason은 logout 하나).
     */
    public void endSession(ChatIdentity identity, SessionEndReason reason) {
        for (ChatChannel channel : ChatChannel.values()) {
            String ownerKey = ownerKey(identity, channel);
            String sessionId = redisTemplate.opsForValue().get(ownerKey);
            if (sessionId == null) {
                continue;
            }
            redisTemplate.delete(sessionKey(sessionId));
            redisTemplate.delete(ownerKey);
            notifyIfMember(sessionId, identity, reason);
        }
    }

    /**
     * 게스트는 프로필 대상이 아니므로 I-20을 생략한다(노션 I-20 정본 — Redis 세션 정리는 유지,
     * FastAPI 맥락은 자체 TTL로 소멸). 회원만 통지하며 userId는 신원 sub(회원 BIGINT).
     */
    private void notifyIfMember(String sessionId, ChatIdentity identity, SessionEndReason reason) {
        if (!ChatIdentity.TYPE_MEMBER.equals(identity.subType())) {
            return;
        }
        llmNotifyClient.notifySessionEnd(sessionId, Long.parseLong(identity.sub()), reason);
    }

    private ChatSessionResponse response(String sessionId, ChatIdentity identity, Long brandId) {
        String ticket = brandId != null
                ? ticketProvider.createSellerTicket(identity, sessionId, brandId)
                : ticketProvider.createTicket(identity, sessionId);
        return new ChatSessionResponse(sessionId, sessionTtl().toSeconds(), ticket,
                ticketProvider.ttlSeconds(), sseEndpoint(brandId != null));
    }

    /** 판매자 챗은 별도 주소 {AI_SERVER}/seller/chat 확정(04 S-4 · 2026-07-18 합의) — 전체 엔드포인트를 내려준다 */
    private String sseEndpoint(boolean seller) {
        String base = llmProperties.sseUrl().replaceAll("/+$", "");
        return base + (seller ? "/seller/chat" : "/chat");
    }

    /** 세션·owner 키를 함께 민다 — 한쪽만 남으면 발급이 죽은 세션을 재사용하게 된다 */
    private void slide(String sessionId, String ownerKey) {
        redisTemplate.expire(sessionKey(sessionId), sessionTtl());
        redisTemplate.expire(ownerKey, ownerTtl());
    }

    private Duration sessionTtl() {
        return Duration.ofMinutes(chatProperties.sessionTtlMinutes());
    }

    /**
     * owner는 세션보다 1분 길게 (07 §3-2) — 두 키의 expire가 원자적이지 않아, 갱신 도중 죽으면
     * owner가 먼저 만료될 수 있다. 그러면 세션이 살아 있는데 다음 CH-1이 새 세션을 발급해
     * <b>조용한 맥락 초기화</b>가 된다. 반대 방향(owner만 남음)은 reuseActive가 "없음"으로 처리하므로,
     * 비대칭은 위험한 방향만 구조적으로 불가능하게 만든다. 세션 TTL을 늘려도 +1분은 그대로 따라간다.
     */
    private Duration ownerTtl() {
        return sessionTtl().plusMinutes(1);
    }

    private static String sessionValue(ChatIdentity identity, ChatChannel channel, Long brandId) {
        return identity.subType() + DELIMITER + identity.sub() + DELIMITER + channel.name()
                + (brandId != null ? DELIMITER + brandId : "");
    }

    /** 세션 값의 4번째 칸은 S-4 SELLER 세션에만 있다 */
    private static Long brandIdOf(String value) {
        String[] parts = value.split("\\" + DELIMITER);
        return parts.length >= 4 ? Long.valueOf(parts[3]) : null;
    }

    private static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    /** 채널을 키에 넣어 같은 신원의 SHOPPING·SELLER 세션이 공존하게 한다(노션 CH-1 2026-07-31) */
    private static String ownerKey(ChatIdentity identity, ChatChannel channel) {
        return OWNER_KEY_PREFIX + identity.subType() + ":" + identity.sub() + ":" + channel.name();
    }
}
