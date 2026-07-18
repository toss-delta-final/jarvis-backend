package com.jarvis.global.event;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * E-1의 202 즉시 응답을 위한 비동기 적재 (04 §8). 실패는 로그만 — 행동 이벤트는 유실 감수.
 * 중복은 INSERT 전 검증 후 무시 — INSERT IGNORE는 중복 외 오류까지 삼키므로 금지 (02 D35).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorEventAppender {

    private final BehaviorEventRepository behaviorEventRepository;

    @Async
    @Transactional
    public void append(List<BehaviorEvent> events) {
        try {
            List<String> clientEventIds = events.stream()
                    .map(BehaviorEvent::getClientEventId)
                    .filter(Objects::nonNull)
                    .toList();
            Set<String> existing = clientEventIds.isEmpty() ? Set.of()
                    : new HashSet<>(behaviorEventRepository.findExistingClientEventIds(clientEventIds));
            List<BehaviorEvent> fresh = events.stream()
                    .filter(e -> e.getClientEventId() == null || !existing.contains(e.getClientEventId()))
                    .toList();
            if (fresh.size() < events.size()) {
                log.info("behavior_events 중복 {}건 무시 (D35)", events.size() - fresh.size());
            }
            behaviorEventRepository.saveAll(fresh);
        } catch (Exception e) {
            log.warn("behavior_events 적재 실패 — 배치 {}건 유실", events.size(), e);
        }
    }
}
