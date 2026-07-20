package com.jarvis.chat;

import com.jarvis.chat.dto.ChatSessionResponse;
import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * CH-1/CH-1b (04 §6) — 세션은 Redis TTL 10분 sliding, 대화 내용은 저장하지 않는다(03 §1).
 * 신원별 활성 세션을 1개로 유지(owner 인덱스)해 "새 대화"·로그아웃 시 I-20 통지 대상을 찾는다.
 */
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

    /** CH-1 — 세션 + 티켓 동시 발급. 같은 신원의 기존 세션은 "새 대화"로 정리(I-20 통지) */
    public ChatSessionResponse issueSession(ChatIdentity identity, ChatChannel channel) {
        return issue(identity, channel, null);
    }

    /** S-4 — SELLER 세션 (04 §7). brandId를 세션 값에 보관해 CH-1b 재발급도 SELLER 티켓 유지 */
    public ChatSessionResponse issueSellerSession(ChatIdentity identity, Long brandId) {
        return issue(identity, ChatChannel.SELLER, brandId);
    }

    private ChatSessionResponse issue(ChatIdentity identity, ChatChannel channel, Long brandId) {
        String ownerKey = ownerKey(identity);
        String previousSessionId = redisTemplate.opsForValue().get(ownerKey);
        if (previousSessionId != null) {
            redisTemplate.delete(sessionKey(previousSessionId));
            llmNotifyClient.notifySessionEnd(
                    previousSessionId, identity.sub(), SessionEndReason.NEW_CONVERSATION);
        }
        String sessionId = UUID.randomUUID().toString();
        Duration ttl = sessionTtl();
        String value = identity.subType() + DELIMITER + identity.sub() + DELIMITER + channel.name()
                + (brandId != null ? DELIMITER + brandId : "");
        redisTemplate.opsForValue().set(sessionKey(sessionId), value, ttl);
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
        Duration ttl = sessionTtl();
        redisTemplate.expire(sessionKey(sessionId), ttl);
        redisTemplate.expire(ownerKey(identity), ttl);
        Long brandId = parts.length >= 4 ? Long.valueOf(parts[3]) : null;
        return response(sessionId, identity, brandId);
    }

    /** 로그아웃 등 종료 트리거 — 활성 세션이 있으면 삭제 + I-20 통지 (05 §2-1) */
    public void endSession(ChatIdentity identity, SessionEndReason reason) {
        String ownerKey = ownerKey(identity);
        String sessionId = redisTemplate.opsForValue().get(ownerKey);
        if (sessionId == null) {
            return;
        }
        redisTemplate.delete(sessionKey(sessionId));
        redisTemplate.delete(ownerKey);
        llmNotifyClient.notifySessionEnd(sessionId, identity.sub(), reason);
    }

    private ChatSessionResponse response(String sessionId, ChatIdentity identity, Long brandId) {
        String ticket = brandId != null
                ? ticketProvider.createSellerTicket(identity, brandId)
                : ticketProvider.createTicket(identity);
        return new ChatSessionResponse(sessionId, sessionTtl().toSeconds(), ticket,
                ticketProvider.ttlSeconds(), sseEndpoint(brandId != null));
    }

    /** 판매자 챗은 별도 주소 {AI_SERVER}/seller/chat 확정(04 S-4 · 2026-07-18 합의) — 전체 엔드포인트를 내려준다 */
    private String sseEndpoint(boolean seller) {
        String base = llmProperties.sseUrl().replaceAll("/+$", "");
        return base + (seller ? "/seller/chat" : "/chat");
    }

    private Duration sessionTtl() {
        return Duration.ofMinutes(chatProperties.sessionTtlMinutes());
    }

    private static String sessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private static String ownerKey(ChatIdentity identity) {
        return OWNER_KEY_PREFIX + identity.subType() + ":" + identity.sub();
    }
}
