package com.jarvis.global.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.global.auth.TokenHasher;
import com.jarvis.global.event.dto.EventBatchRequest;
import com.jarvis.member.GuestRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * E-1 서버 처리 4단계 (04 §8): ① 신원은 JWT·쿠키에서 주입(body 무시) ② 8종 외 폐기+경고
 * ③ session_start에 ipHash 주입 ④ created_at=서버 수신 시각으로 적재(중복 UUID 무시).
 * 게스트 존재 확인은 도메인 규칙이 아닌 FK 정합용 조회라 GuestRepository 직접 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    /** 8종 화이트리스트 (02 §4) */
    private static final Set<String> EVENT_TYPES = Set.of(
            "session_start", "page_view", "search", "product_view",
            "add_to_cart", "checkout_start", "purchase_complete", "login");
    private static final String SESSION_START = "session_start";

    private final BehaviorEventAppender behaviorEventAppender;
    private final GuestRepository guestRepository;
    private final ObjectMapper objectMapper;

    public void collect(EventBatchRequest request, Long memberId, String guestId, String clientIp) {
        String validGuestId = resolveGuestId(guestId);
        LocalDateTime receivedAt = LocalDateTime.now();
        Set<String> seenIds = new HashSet<>();
        List<BehaviorEvent> events = new ArrayList<>();
        for (EventBatchRequest.EventItem item : request.events()) {
            if (!EVENT_TYPES.contains(item.eventType())) {
                log.warn("화이트리스트 외 eventType 폐기: {}", item.eventType());
                continue;
            }
            if (item.id() != null && !seenIds.add(item.id())) {
                continue; // 배치 내 중복 (02 D35)
            }
            events.add(BehaviorEvent.record(memberId, validGuestId, item.sessionKey(), item.id(),
                    item.eventType(), item.productId(),
                    serializeProperties(item, clientIp), receivedAt));
        }
        if (!events.isEmpty()) {
            behaviorEventAppender.append(events);
        }
    }

    /** 쿠키의 guest_id가 DB에 없으면(로컬 DB 초기화 등) FK 위반으로 배치가 죽지 않게 무주체 처리 */
    private String resolveGuestId(String guestId) {
        if (guestId == null || !guestRepository.existsById(guestId)) {
            return null;
        }
        return guestId;
    }

    private String serializeProperties(EventBatchRequest.EventItem item, String clientIp) {
        Map<String, Object> properties = item.properties() == null
                ? new HashMap<>() : new HashMap<>(item.properties());
        if (SESSION_START.equals(item.eventType())) {
            properties.put("ipHash", TokenHasher.sha256Hex(clientIp)); // 서버 주입 (02 §4)
        }
        if (properties.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(properties);
        } catch (JsonProcessingException e) {
            log.warn("properties 직렬화 실패 — null로 적재", e);
            return null;
        }
    }
}
