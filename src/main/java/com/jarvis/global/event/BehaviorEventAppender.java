package com.jarvis.global.event;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * E-1의 202 즉시 응답을 위한 비동기 적재 (04 §8). 실패는 로그만 — 행동 이벤트는 유실 감수.
 * 중복은 INSERT 전 검증 후 무시 — INSERT IGNORE는 중복 외 오류까지 삼키므로 금지 (02 D35).
 * 사전 검증을 통과한 뒤 경합으로 UNIQUE에 걸리면(같은 배치 동시 재전송) 배치 저장이 통째로
 * 롤백되므로, 그때만 건별 저장으로 내려가 충돌한 건만 버린다 — 정상 이벤트 동반 유실 방지.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorEventAppender {

    private final BehaviorEventRepository behaviorEventRepository;
    private final TransactionTemplate transactionTemplate;

    @Async
    public void append(List<BehaviorEvent> events) {
        try {
            List<BehaviorEvent> fresh = dropKnownDuplicates(events);
            if (fresh.isEmpty()) {
                return;
            }
            try {
                transactionTemplate.executeWithoutResult(s -> behaviorEventRepository.saveAll(fresh));
            } catch (DataIntegrityViolationException e) {
                saveIndividually(fresh);
            }
        } catch (Exception e) {
            log.warn("behavior_events 적재 실패 — 배치 {}건 유실", events.size(), e);
        }
    }

    private List<BehaviorEvent> dropKnownDuplicates(List<BehaviorEvent> events) {
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
        return fresh;
    }

    /** 배치가 UNIQUE 경합으로 깨진 경우 — 건별 트랜잭션으로 재시도해 충돌 건만 버린다 */
    private void saveIndividually(List<BehaviorEvent> fresh) {
        int dropped = 0;
        for (BehaviorEvent event : fresh) {
            try {
                transactionTemplate.executeWithoutResult(s -> behaviorEventRepository.save(event));
            } catch (DataIntegrityViolationException dup) {
                dropped++;
            }
        }
        log.info("behavior_events 배치 경합 — 건별 저장으로 {}건 중 {}건만 중복 폐기",
                fresh.size(), dropped);
    }
}
