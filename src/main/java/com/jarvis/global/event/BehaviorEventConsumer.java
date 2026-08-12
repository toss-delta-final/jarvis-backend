package com.jarvis.global.event;

import com.jarvis.global.config.KafkaConfig;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 컨슈머 그룹 A — persister (08 §0). 토픽의 이벤트를 {@code behavior_events}에 적재한다.
 *
 * <p>배치 리스너인 이유: 적재는 원래 배치 INSERT였고(E-1 ④), 건별로 바꾸면 왕복이 건수만큼 는다.
 *
 * <p>멱등: 같은 이벤트를 다시 받아도 {@code client_event_id} UNIQUE가 막는다 (02 D35, 08 D3).
 * 그래서 오프셋 되감기 재처리도, 폴백과의 이중 적재도 안전하다.
 *
 * <p>앱 안에서 도는 이유는 08 D9 — 적재는 이 계층이 원래 하던 일이라 별도 배포 단위를 만들지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorEventConsumer {

    private final BehaviorEventAppender behaviorEventAppender;

    @KafkaListener(topics = KafkaConfig.TOPIC, groupId = KafkaConfig.PERSISTER_GROUP)
    public void consume(List<BehaviorEventMessage> messages) {
        List<BehaviorEvent> events = messages.stream().map(BehaviorEventMessage::toEntity).toList();
        behaviorEventAppender.append(events);
        log.debug("behavior_events {}건 적재 (컨슈머 A)", events.size());
    }
}
