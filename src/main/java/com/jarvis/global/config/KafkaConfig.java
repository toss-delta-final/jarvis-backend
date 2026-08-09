package com.jarvis.global.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

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

    /** 재시도까지 실패한 레코드가 가는 곳. 이름은 DeadLetterPublishingRecoverer 기본 규칙(.DLT) */
    public static final String BEHAVIOR_EVENTS_DLT = BEHAVIOR_EVENTS_TOPIC + ".DLT";

    private static final int PARTITIONS = 3;

    @Bean
    public NewTopic behaviorEventsTopic() {
        // 복제본 1 — 단일 브로커(08 D6). 운영 전환 시 3대 + RF3.
        return TopicBuilder.name(BEHAVIOR_EVENTS_TOPIC)
                .partitions(PARTITIONS)
                .replicas(1)
                .build();
    }

    /**
     * DLT도 파티션 수를 맞춘다 — 기본 recoverer가 원본과 <b>같은 파티션 번호</b>로 보내므로,
     * DLT가 1파티션이면 2번 파티션에서 실패한 레코드를 옮기다 그것마저 실패한다.
     */
    @Bean
    public NewTopic behaviorEventsDltTopic() {
        return TopicBuilder.name(BEHAVIOR_EVENTS_DLT)
                .partitions(PARTITIONS)
                .replicas(1)
                .build();
    }

    /**
     * 컨슈머 실패 처리 — 1초 간격 3회 재시도 후 DLT로 보낸다.
     *
     * <p>재시도가 짧은 이유는 실패 대부분이 DB 순간 장애라 몇 초면 회복되기 때문이고,
     * 무한 재시도를 하지 않는 이유는 역직렬화 불가 같은 <b>독성 레코드</b> 하나가 파티션을
     * 영구히 막기 때문이다. DLT는 폐기가 아니다 — 보존 7일 안에 되감아 재처리할 수 있다.
     */
    @Bean
    public CommonErrorHandler behaviorEventErrorHandler(KafkaOperations<?, ?> kafkaOperations) {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaOperations),
                new FixedBackOff(1_000L, 3L));
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.ERROR);
        return handler;
    }
}
