package com.jarvis.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

import com.jarvis.global.config.KafkaConfig;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * 토픽 왕복 검증 (08 §5) — 프로듀서가 실은 것을 컨슈머 A가 같은 값으로 되돌려 받는지 본다.
 * 단위 테스트로는 못 잡는 것들이 여기서 걸린다: JSON 직렬화, {@code LocalDateTime} 왕복,
 * 타입 헤더 없이 기본 타입으로 역직렬화, 배치 리스너 수신.
 *
 * <p>브로커는 EmbeddedKafka라 CI에서도 돈다. 적재(DB)는 이 테스트의 관심이 아니라 목으로 끊는다.
 */
@SpringJUnitConfig
@EmbeddedKafka(partitions = 3, topics = KafkaConfig.BEHAVIOR_EVENTS_TOPIC)
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.producer.properties.spring.json.add.type.headers=false",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.ErrorHandlingDeserializer",
        "spring.kafka.consumer.properties.spring.deserializer.value.delegate.class=org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=com.jarvis.global.event",
        "spring.kafka.consumer.properties.spring.json.value.default.type=com.jarvis.global.event.BehaviorEventMessage",
        "spring.kafka.listener.type=batch",
})
class BehaviorEventStreamTest {

    @Configuration
    @EnableKafka
    @EnableConfigurationProperties(org.springframework.boot.autoconfigure.kafka.KafkaProperties.class)
    @Import({KafkaAutoConfiguration.class, KafkaConfig.class,
            BehaviorEventPublisher.class, BehaviorEventConsumer.class})
    static class TestConfig {
    }

    @Autowired BehaviorEventPublisher publisher;
    @MockitoBean BehaviorEventAppender behaviorEventAppender;
    @MockitoBean BehaviorStreamHealth streamHealth;

    @Test
    @DisplayName("발행한 이벤트가 컨슈머 A에 같은 값으로 도착한다 — 시각·추천 귀속 포함")
    void roundTripsThroughTopic() {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 10, 12, 34, 56, 123_456_000);
        LocalDateTime createdAt = occurredAt.plusSeconds(2);
        BehaviorEvent event = BehaviorEvent.record(7L, null, "sess-round-trip", "evt-round-trip",
                "product_click", 101L, "{\"clickTarget\":\"card\"}", occurredAt,
                new EventAttribution("req-1", "list-1", "CHAT", 3), createdAt);

        publisher.publish(List.of(event));

        ArgumentCaptor<List<BehaviorEvent>> captor = ArgumentCaptor.captor();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> verify(behaviorEventAppender, atLeastOnce()).append(captor.capture()));

        assertThat(captor.getValue()).singleElement().satisfies(received -> {
            assertThat(received.getClientEventId()).isEqualTo("evt-round-trip");
            assertThat(received.getMemberId()).isEqualTo(7L);
            assertThat(received.getSessionKey()).isEqualTo("sess-round-trip");
            assertThat(received.getEventType()).isEqualTo("product_click");
            assertThat(received.getProductId()).isEqualTo(101L);
            assertThat(received.getProperties()).isEqualTo("{\"clickTarget\":\"card\"}");
            // 마이크로초까지 살아남아야 한다 — occurred_at은 datetime(6)이고 노출→클릭 간격 분석의 근거다
            assertThat(received.getOccurredAt()).isEqualTo(occurredAt);
            assertThat(received.getCreatedAt()).isEqualTo(createdAt);
            assertThat(received.getRecommendationRequestId()).isEqualTo("req-1");
            assertThat(received.getListId()).isEqualTo("list-1");
            assertThat(received.getSurface()).isEqualTo("CHAT");
            assertThat(received.getPosition()).isEqualTo(3);
        });
    }
}
