package com.jarvis.global.event;

import com.jarvis.global.config.BehaviorStreamProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 행동 이벤트 발행 (08 D2·D7) — 토픽에 싣고, 실패하면 DB에 직접 적재한다.
 *
 * <p><b>왜 폴백인가</b>: 브로커는 복제 없는 단일 노드(08 D6)라 죽을 수 있다. 그때 500을 내면
 * 그 배치는 그대로 유실된다 — FE SDK는 실패 응답을 검사하지 않고 큐를 이미 비운 뒤라
 * 재전송하지 않는다(jarvis-frontend {@code track.ts}, 노션 E-1의 "SDK 재전송" 규정과 어긋난
 * 구현이며 별도 보고 대상). 따라서 폴백이 유실을 막는 유일한 경로다.
 *
 * <p>둘 다 실패하면 예외를 던진다 — E-1은 그때만 500을 내고(계약대로), 담기·추천 경로는
 * 호출부가 삼켜 사용자 플로우를 지킨다.
 *
 * <p>중복은 걱정하지 않는다: produce가 타임아웃으로 실패처럼 보였지만 실제로는 성공한 경우
 * 컨슈머와 폴백이 같은 행을 쓰지만 {@code client_event_id} UNIQUE가 막는다 (02 D35, 08 D3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BehaviorEventPublisher {

    /**
     * produce 응답 대기 상한. 프로듀서 자체가 delivery.timeout.ms=2000으로 끊지만(08 D7),
     * 그 경로가 어긋나도 요청 스레드가 매달리지 않게 두는 백스톱이다.
     */
    private static final Duration SEND_DEADLINE = Duration.ofSeconds(3);

    private final KafkaTemplate<String, BehaviorEventMessage> kafkaTemplate;
    private final BehaviorStreamProperties names;
    private final BehaviorEventAppender behaviorEventAppender;
    private final BehaviorStreamHealth streamHealth;
    private final ProduceCircuitBreaker circuitBreaker;

    /**
     * @throws RuntimeException 토픽·DB 양쪽 적재가 모두 실패한 경우 — 호출부가 500 여부를 정한다
     */
    public void publish(List<BehaviorEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        // 브로커가 죽은 걸 이미 아는 동안은 시도조차 하지 않는다 — 매 요청이 타임아웃을 무는 걸 막는다
        if (!circuitBreaker.allowAttempt()) {
            behaviorEventAppender.append(events);
            return;
        }

        List<BehaviorEvent> undelivered = sendAll(events);
        if (undelivered.isEmpty()) {
            circuitBreaker.recordSuccess();
            return;
        }
        circuitBreaker.recordFailure();
        log.warn("behavior-events 발행 실패 {}건 — DB 직접 적재로 폴백 (08 D7)", undelivered.size());
        // 이 구간 이벤트는 토픽에 없다 = 스트림 파생 지표에도 없다. 읽는 쪽이 DB로 돌아가게 알린다.
        // 차단 중에는 이 표시를 갱신하지 않지만, 10초마다 도는 시험 발행이 실패하며 계속 갱신한다
        streamHealth.markProduceFailure();
        behaviorEventAppender.append(undelivered);
    }

    /** 전량을 비동기로 보낸 뒤 공통 데드라인 안에서 결과를 모은다 — 실패한 건만 돌려준다 */
    private List<BehaviorEvent> sendAll(List<BehaviorEvent> events) {
        List<BehaviorEvent> undelivered = new ArrayList<>();
        List<CompletableFuture<?>> futures = new ArrayList<>(events.size());
        List<BehaviorEvent> inFlight = new ArrayList<>(events.size());

        for (int i = 0; i < events.size(); i++) {
            BehaviorEventMessage message = BehaviorEventMessage.from(events.get(i));
            try {
                futures.add(kafkaTemplate.send(names.topic(),
                        message.partitionKey(), message));
                inFlight.add(events.get(i));
            } catch (Exception e) {
                // ⚠️ send()는 실패를 항상 future로 주지 않는다 — 브로커 메타데이터를 못 받으면
                // ("Topic ... not present in metadata after 1000 ms") **동기적으로 던진다.**
                // 이걸 잡지 않으면 폴백·강등 마커를 통째로 건너뛰고 500이 나가 이벤트가 유실된다
                // (2026-08-10 재현: 브로커가 꺼진 채 기동한 앱의 첫 요청. 캐시된 메타데이터가 있으면
                //  future 경로로 오기 때문에 "브로커를 나중에 죽이는" 테스트로는 잡히지 않았다).
                //
                // 남은 건까지 시도하지 않고 한꺼번에 폴백으로 넘긴다 — 브로커 단위 실패라 결과가 같고,
                // 건당 max.block.ms(1s)를 다시 기다리면 배치 100건이 100초가 된다.
                log.warn("behavior-events 발행이 동기 예외로 실패 — 남은 {}건까지 DB 폴백으로 넘긴다",
                        events.size() - i, e);
                undelivered.addAll(events.subList(i, events.size()));
                break;
            }
        }

        // 레코드들은 프로듀서 I/O 스레드에서 동시에 나가므로 대기 시간이 건수에 비례하지 않는다.
        // 그래도 배치가 클 때 합이 늘어나지 않도록 데드라인을 공유한다.
        long deadlineNanos = System.nanoTime() + SEND_DEADLINE.toNanos();
        for (int i = 0; i < futures.size(); i++) {
            long remaining = deadlineNanos - System.nanoTime();
            try {
                futures.get(i).get(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                undelivered.addAll(inFlight.subList(i, inFlight.size()));
                return undelivered;
            } catch (Exception e) {
                if (undelivered.isEmpty()) {
                    log.warn("behavior-events produce 실패 (clientEventId={})",
                            inFlight.get(i).getClientEventId(), e);
                }
                undelivered.add(inFlight.get(i));
            }
        }
        return undelivered;
    }
}
