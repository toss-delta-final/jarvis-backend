package com.jarvis.global.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 행동 이벤트 스트림 (08 D1) — 토픽 하나에 소비자 그룹 둘(persister·visitor-tracker)이 붙는다.
 *
 * <p>토픽을 여기서 선언하는 이유: 브로커 자동 생성에 맡기면 파티션 수가 브로커 기본값을 따라가
 * 컨슈머 병렬도가 조용히 달라진다. 파티션 수는 그룹의 최대 병렬도라 설계값(3)으로 고정한다.
 */
@Configuration
public class KafkaConfig {

    /** 프로듀서·컨슈머가 공유하는 토픽 이름. 앱 내부 계약이라 설정값으로 빼지 않는다. */
    public static final String BEHAVIOR_EVENTS_TOPIC = "behavior-events";

    @Bean
    public NewTopic behaviorEventsTopic() {
        // 복제본 1 — 단일 브로커(08 D6). 운영 전환 시 3대 + RF3.
        return TopicBuilder.name(BEHAVIOR_EVENTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
