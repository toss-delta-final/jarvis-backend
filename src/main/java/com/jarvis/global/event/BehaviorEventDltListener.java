package com.jarvis.global.event;

import com.jarvis.global.config.KafkaConfig;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DLT 감시 (08 D3) — 적재에 끝내 실패한 레코드를 <b>관측 가능하게</b> 만든다.
 *
 * <p><b>왜 필요한가</b>: 컨슈머가 3회 재시도 후에도 실패하면 레코드는 DLT로 옮겨진다. 그 자체는
 * 설계대로지만, <b>읽는 것도 알리는 것도 없으면 쌓여도 아무도 모른다</b> — 이 파이프라인이 내내
 * 지켜온 "조용한 실패를 관측 가능한 실패로 바꾼다"는 원칙에 어긋나는 마지막 지점이었다.
 *
 * <p><b>소비해도 사라지지 않는다</b>(1강의 도서관) — 여기서 읽는 건 알림용이고, 레코드는 보존
 * 기간(7일) 동안 토픽에 남는다. 원인을 고친 뒤 오프셋을 되감아 재처리할 수 있다.
 *
 * <p><b>로그 폭주 방지</b>: DB가 통째로 죽으면 모든 배치가 DLT로 밀려 수천 건이 될 수 있다.
 * 분당 상한을 두고 나머지는 세기만 해 요약으로 남긴다 — 원인 파악에는 앞의 몇 건이면 충분하고,
 * 나머지는 "몇 건인가"가 정보다.
 */
@Slf4j
@Component
public class BehaviorEventDltListener {

    /** 분당 개별 로그 상한 — 원인은 앞 몇 건이면 드러난다 */
    private static final int DETAIL_LOG_LIMIT_PER_MINUTE = 10;
    private static final int PAYLOAD_PREVIEW_CHARS = 200;

    private final AtomicLong loggedThisMinute = new AtomicLong();
    private final AtomicLong suppressedThisMinute = new AtomicLong();
    private final AtomicLong totalSinceStartup = new AtomicLong();

    @KafkaListener(topics = KafkaConfig.DLT, groupId = KafkaConfig.DLT_MONITOR_GROUP,
            containerFactory = "dltListenerContainerFactory")
    public void onDeadLetter(ConsumerRecord<String, byte[]> record) {
        totalSinceStartup.incrementAndGet();
        if (loggedThisMinute.incrementAndGet() > DETAIL_LOG_LIMIT_PER_MINUTE) {
            suppressedThisMinute.incrementAndGet();
            return;
        }
        log.error("behavior_events 적재 최종 실패 — DLT 적치 (원본 {}-{}@{}, key={}, 원인={}: {}) payload={}",
                header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC),
                intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION),
                longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET),
                record.key(),
                header(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                preview(record.value()));
    }

    /** 억제된 건수를 요약으로 남기고 분당 창을 연다 — 폭주 중에도 총량은 로그에 남는다 */
    @Scheduled(fixedDelay = 60_000)
    public void flushSuppressed() {
        long suppressed = suppressedThisMinute.getAndSet(0);
        loggedThisMinute.set(0);
        if (suppressed > 0) {
            log.error("DLT 적치 {}건 추가 (상세 로그 생략, 기동 후 누적 {}건)",
                    suppressed, totalSinceStartup.get());
        }
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? "?" : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String intHeader(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value().length < Integer.BYTES
                ? "?" : Integer.toString(ByteBuffer.wrap(header.value()).getInt());
    }

    private static String longHeader(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value().length < Long.BYTES
                ? "?" : Long.toString(ByteBuffer.wrap(header.value()).getLong());
    }

    /** 원문 전체를 남기지 않는다 — 진단에는 앞부분이면 되고, 로그가 커지면 그 자체가 부담이다 */
    private static String preview(byte[] value) {
        if (value == null) {
            return "(null)";
        }
        String text = new String(value, StandardCharsets.UTF_8);
        return text.length() <= PAYLOAD_PREVIEW_CHARS
                ? text : text.substring(0, PAYLOAD_PREVIEW_CHARS) + "…(" + text.length() + "자)";
    }
}
