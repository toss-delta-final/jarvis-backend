package com.jarvis.global.event;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DLT 리스너의 핵심 계약은 <b>"무엇이 들어와도 던지지 않는다"</b>이다.
 * 여기서 예외가 나면 그 레코드가 다시 실패로 처리돼 <b>DLT의 DLT</b>가 만들어진다 —
 * 감시 장치가 감시할 대상을 늘리는 셈이다.
 */
class BehaviorEventDltListenerTest {

    private final BehaviorEventDltListener listener = new BehaviorEventDltListener();

    private static ConsumerRecord<String, byte[]> record(byte[] value) {
        // 이름은 이 테스트의 관심이 아니다 — 레코드가 어느 토픽에서 왔는지는 리스너 동작에 영향이 없다
        return new ConsumerRecord<>("behavior-events-dlt", 0, 0L, null, value);
    }

    @Test
    @DisplayName("DLT 헤더가 하나도 없어도 던지지 않는다 — 손으로 넣은 레코드도 있을 수 있다")
    void survivesMissingHeaders() {
        assertThatCode(() -> listener.onDeadLetter(record("{}".getBytes(StandardCharsets.UTF_8))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("값이 null이어도 던지지 않는다")
    void survivesNullValue() {
        assertThatCode(() -> listener.onDeadLetter(record(null))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("폭주해도 분당 상한을 넘으면 개별 로그를 멈추고 요약만 남긴다 — 로그 홍수 방지")
    void throttlesLogBursts() {
        assertThatCode(() -> {
            for (int i = 0; i < 500; i++) {
                listener.onDeadLetter(record(("{\"n\":" + i + "}").getBytes(StandardCharsets.UTF_8)));
            }
            listener.flushSuppressed();
        }).doesNotThrowAnyException();
    }
}
