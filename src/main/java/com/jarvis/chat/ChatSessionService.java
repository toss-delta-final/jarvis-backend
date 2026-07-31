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
 * owner 인덱스는 채널별로 두어 SHOPPING·CS·SELLER 세션이 서로를 밀어내지 않게 한다.
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
        Duration ttl = sessionTtl();
        String value = identity.subType() + DELIMITER + identity.sub() + DELIMITER + channel.name()
                + (brandId != null ? DELIMITER + brandId : "");
        redisTemplate.opsForValue().set(sessionKey(sessionId), value, ttl);
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(ownerKey, sessionId, ttl))) {
            return response(sessionId, identity, brandId);
        }
        Optional<ChatSessionResponse> winner = reuseActive(ownerKey, identity);
        if (winner.isPresent()) {
            redisTemplate.delete(sessionKey(sessionId));
            return winner.get();
        }
        redisTemplate.opsForValue().set(ownerKey, sessionId, ttl);
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
     * 주의: issue()의 "새 대화" 정리는 새 세션 발급과의 순서 보장이 필요해 동기 경로를 유지한다.
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
        clearSessions(identity, reason);
    }

    /**
     * 게스트 승계 시 세션 정리 — 게스트는 프로필 대상이 아니라 I-20을 발화하지 않으므로 통지 없이 비운다
     * (노션 I-20 정본). Phase C에서 "정리"가 "승계"(I-23)로 바뀔 자리다.
     */
    @Async
    public void discardSessionsAsync(ChatIdentity identity) {
        try {
            clearSessions(identity, null);
        } catch (RuntimeException e) {
            log.warn("채팅 세션 정리 실패 — TTL 소멸에 위임. identity={}:{}",
                    identity.subType(), identity.sub(), e);
        }
    }

    /** reason이 null이면 통지 없이 Redis만 비운다 */
    private void clearSessions(ChatIdentity identity, SessionEndReason reason) {
        for (ChatChannel channel : ChatChannel.values()) {
            String ownerKey = ownerKey(identity, channel);
            String sessionId = redisTemplate.opsForValue().get(ownerKey);
            if (sessionId == null) {
                continue;
            }
            redisTemplate.delete(sessionKey(sessionId));
            redisTemplate.delete(ownerKey);
            if (reason != null) {
                notifyIfMember(sessionId, identity, reason);
            }
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
        Duration ttl = sessionTtl();
        redisTemplate.expire(sessionKey(sessionId), ttl);
        redisTemplate.expire(ownerKey, ttl);
    }

    private Duration sessionTtl() {
        return Duration.ofMinutes(chatProperties.sessionTtlMinutes());
    }

    /** 세션 값의 4번째 칸은 S-4 SELLER 세션에만 있다 */
    private static Long brandIdOf(String value) {
        String[] parts = value.split("\\" + DELIMITER);
        return parts.length >= 4 ? Long.valueOf(parts[3]) : null;
    }

    private static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    /** 채널을 키에 넣어 같은 신원의 SHOPPING·CS·SELLER 세션이 공존하게 한다(노션 CH-1 2026-07-31) */
    private static String ownerKey(ChatIdentity identity, ChatChannel channel) {
        return OWNER_KEY_PREFIX + identity.subType() + ":" + identity.sub() + ":" + channel.name();
    }
}
