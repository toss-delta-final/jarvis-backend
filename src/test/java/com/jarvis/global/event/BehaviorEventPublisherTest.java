package com.jarvis.global.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jarvis.global.config.KafkaConfig;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class BehaviorEventPublisherTest {

    @Mock KafkaTemplate<String, BehaviorEventMessage> kafkaTemplate;
    @Mock BehaviorEventAppender behaviorEventAppender;
    @Mock BehaviorStreamHealth streamHealth;

    @InjectMocks BehaviorEventPublisher publisher;

    @Captor ArgumentCaptor<List<BehaviorEvent>> fallbackCaptor;
    @Captor ArgumentCaptor<BehaviorEventMessage> messageCaptor;

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

        verify(kafkaTemplate).send(eq(KafkaConfig.BEHAVIOR_EVENTS_TOPIC), eq("sess-42"),
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
    @DisplayName("빈 배치는 브로커를 건드리지 않는다")
    void skipsEmptyBatch() {
        publisher.publish(List.of());

        verifyNoInteractions(kafkaTemplate, behaviorEventAppender, streamHealth);
    }
}
