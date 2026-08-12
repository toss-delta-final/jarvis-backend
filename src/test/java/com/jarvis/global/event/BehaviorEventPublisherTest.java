package com.jarvis.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jarvis.global.config.BehaviorStreamProperties;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class BehaviorEventPublisherTest {

    /** 접두어 없는 기본 이름 — 이름 자체는 이 테스트의 관심이 아니다 (yml 배선은 BehaviorStreamPropertiesTest) */
    private static final BehaviorStreamProperties NAMES = new BehaviorStreamProperties("",
            "behavior-events", "behavior-events-dlt",
            new BehaviorStreamProperties.Groups("persister", "visitor-tracker", "dlt-monitor"));

    @Mock KafkaTemplate<String, BehaviorEventMessage> kafkaTemplate;
    @Mock BehaviorEventAppender behaviorEventAppender;
    @Mock BehaviorStreamHealth streamHealth;
    @Mock ProduceCircuitBreaker circuitBreaker;

    BehaviorEventPublisher publisher;

    @Captor ArgumentCaptor<List<BehaviorEvent>> fallbackCaptor;
    @Captor ArgumentCaptor<BehaviorEventMessage> messageCaptor;

    @BeforeEach
    void closedByDefault() {
        lenient().when(circuitBreaker.allowAttempt()).thenReturn(true);
        publisher = new BehaviorEventPublisher(kafkaTemplate, NAMES, behaviorEventAppender,
                streamHealth, circuitBreaker);
    }

    private static BehaviorEvent event(String clientEventId, String sessionKey) {
        LocalDateTime now = LocalDateTime.now();
        return BehaviorEvent.record(1L, null, sessionKey, clientEventId, "product_view", 101L,
                "{\"price\":1000}", now, EventAttribution.NONE, now);
    }

    private static CompletableFuture<SendResult<String, BehaviorEventMessage>> sent() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, BehaviorEventMessage>> failed() {
        return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
    }

    @Test
    @DisplayName("발행에 성공하면 DB로 폴백하지 않는다 — 적재는 컨슈머가 맡는다")
    void publishesToTopicWithoutFallback() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(sent());

        publisher.publish(List.of(event("id-1", "sess-1")));

        verifyNoInteractions(behaviorEventAppender, streamHealth);
    }

    @Test
    @DisplayName("파티션 키는 sessionKey — 같은 세션의 순서가 한 파티션 안에서 보존된다 (08 D1)")
    void usesSessionKeyAsPartitionKey() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(sent());

        publisher.publish(List.of(event("id-1", "sess-42")));

        verify(kafkaTemplate).send(eq(NAMES.topic()), eq("sess-42"),
                messageCaptor.capture());
        assertThat(messageCaptor.getValue().clientEventId()).isEqualTo("id-1");
    }

    @Test
    @DisplayName("브로커가 죽으면 실패한 건만 DB로 폴백한다 (08 D7)")
    void fallsBackToDatabaseOnProduceFailure() {
        when(kafkaTemplate.send(anyString(), eq("sess-ok"), any())).thenReturn(sent());
        when(kafkaTemplate.send(anyString(), eq("sess-fail"), any())).thenReturn(failed());

        publisher.publish(List.of(event("id-ok", "sess-ok"), event("id-fail", "sess-fail")));

        verify(streamHealth).markProduceFailure();  // 읽는 쪽이 DB로 돌아가게 알린다 (08 D5)
        verify(behaviorEventAppender).append(fallbackCaptor.capture());
        assertThat(fallbackCaptor.getValue())
                .extracting(BehaviorEvent::getClientEventId)
                .containsExactly("id-fail");
    }

    @Test
    @DisplayName("토픽·DB 양쪽이 실패하면 예외를 던진다 — E-1은 그때만 500을 낸다")
    void throwsWhenBothTopicAndDatabaseFail() {
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(failed());
        doThrow(new RuntimeException("db down")).when(behaviorEventAppender).append(anyList());

        assertThatThrownBy(() -> publisher.publish(List.of(event("id-1", "sess-1"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("db down");
    }

    @Test
    @DisplayName("send()가 동기 예외를 던져도 DB로 폴백한다 — 브로커 메타데이터 미확보 (2026-08-10 회귀)")
    void fallsBackWhenSendThrowsSynchronously() {
        // 브로커가 꺼진 채 기동하면 send()는 future가 아니라 그 자리에서 던진다
        // ("Topic ... not present in metadata after 1000 ms"). 종전 구현은 이걸 놓쳐
        // 폴백·강등 마커를 건너뛰고 500을 냈고, FE가 재전송하지 않으므로 그대로 유실됐다.
        when(kafkaTemplate.send(anyString(), anyString(), any()))
                .thenThrow(new org.springframework.kafka.KafkaException("Send failed"));

        publisher.publish(List.of(event("id-1", "sess-1"), event("id-2", "sess-2")));

        verify(streamHealth).markProduceFailure();
        verify(behaviorEventAppender).append(fallbackCaptor.capture());
        // 첫 건에서 터지면 남은 건도 같은 이유로 실패하므로 한꺼번에 넘긴다 — 건당 재시도하면
        // 배치 100건이 max.block.ms × 100이 된다
        assertThat(fallbackCaptor.getValue())
                .extracting(BehaviorEvent::getClientEventId)
                .containsExactly("id-1", "id-2");
    }

    @Test
    @DisplayName("차단 중이면 produce를 시도조차 하지 않고 바로 DB로 간다 — 요청마다 타임아웃을 물지 않게")
    void skipsProduceWhileCircuitOpen() {
        when(circuitBreaker.allowAttempt()).thenReturn(false);

        publisher.publish(List.of(event("id-1", "sess-1")));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
        verify(behaviorEventAppender).append(fallbackCaptor.capture());
        assertThat(fallbackCaptor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("빈 배치는 브로커를 건드리지 않는다")
    void skipsEmptyBatch() {
        publisher.publish(List.of());

        verifyNoInteractions(kafkaTemplate, behaviorEventAppender, streamHealth);
    }
}
