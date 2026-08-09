package com.jarvis.product;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 변경의 단일 관문 (02 D33) — 조건부 차감·취소반품 복원·품절/재입고 로그를 한 트랜잭션으로 묶는다.
 * OrderStatusChanger가 상태 전이와 로그에 대해 하는 역할을 재고에 대해 한다.
 *
 * <p>조회 전용 {@link ProductService}와 나눠 둔 건 바뀌는 이유가 달라서다 — 저쪽은 "상품을 어떻게
 * 보여주나"(응답 모양·정렬)로 바뀌고 여기는 "재고를 어떻게 다루나"(차감 정책·로그 규칙)로 바뀐다.
 *
 * <p>호출자의 트랜잭션 안에서 동작한다(REQUIRED). 보상 복원은 같은 트랜잭션에서 조건부 UPDATE를
 * 되돌리는 것이지 커밋된 차감의 취소가 아니다 — 새 트랜잭션으로 떼면 그 성질이 깨진다.
 *
 * <p><b>2026-08-09 개정</b> — 차감 단위가 상품에서 <b>(상품, 옵션)</b>으로 내려갔다(02 D33 개정).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductStockService {

    private final ProductStockRepository productStockRepository;
    private final ProductChangeLogRepository productChangeLogRepository;

    /** 차감 대상 — 옵션 없는 상품은 {@code optionId}가 null이다 */
    public record StockKey(Long productId, Long optionId) {
    }

    // 옵션 없는 상품(null)을 먼저 잡는다 — 순서만 전 호출자에서 같으면 되고, 무엇이 먼저인지는 중요치 않다
    private static final Comparator<StockKey> LOCK_ORDER = Comparator
            .comparing(StockKey::productId)
            .thenComparing(StockKey::optionId, Comparator.nullsFirst(Comparator.naturalOrder()));

    /**
     * 여러 (상품, 옵션)의 재고를 함께 차감한다. 하나라도 부족하면 이미 차감한 분을 되돌리고 {@code false}.
     * 결제 성공(PAID 전이)과 같은 트랜잭션에서만 호출 — 부분 차감 상태로 커밋되면 안 된다.
     *
     * <p>{@link #LOCK_ORDER} 오름차순으로 잠근다 — 동시 주문이 서로 다른 순서로 같은 행들을 잡으면
     * 데드락이 난다. 호출자가 정렬해 넘겨주길 기대하지 않고 여기서 보장한다(호출자가 늘면 놓치는 쪽이 생긴다).
     *
     * @param quantities (상품, 옵션) → 차감 수량
     * @return 전 품목 차감 성공 여부. false면 재고가 호출 전 상태로 돌아가 있다
     */
    @Transactional
    public boolean deduct(Map<StockKey, Integer> quantities) {
        List<Map.Entry<StockKey, Integer>> applied = new ArrayList<>();
        // 품절 로그는 버퍼에 모았다가 전 품목 차감 성공 후에만 저장 — 일부 실패로 보상 복원되면
        // 허위 품절 로그가 남지 않도록 (02 D32·D33)
        List<ProductChangeLog> stockOutLogs = new ArrayList<>();
        List<Map.Entry<StockKey, Integer>> ordered = quantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(LOCK_ORDER))
                .toList();

        for (Map.Entry<StockKey, Integer> entry : ordered) {
            StockKey key = entry.getKey();
            int qty = entry.getValue();
            if (productStockRepository.deduct(key.productId(), key.optionId(), qty) == 0) {
                applied.forEach(done -> productStockRepository.restore(
                        done.getKey().productId(), done.getKey().optionId(), done.getValue()));
                return false;
            }
            applied.add(entry);
            if (productStockRepository.findQuantity(key.productId(), key.optionId()).orElse(-1) == 0) {
                // 주문에 의한 재고 -1은 미기록, 품절 전환(new_value=0)만 기록 (02 D32·D33)
                stockOutLogs.add(ProductChangeLog.ofOption(key.productId(), key.optionId(),
                        ProductChangeType.STOCK, String.valueOf(qty), "0"));
            }
        }
        if (!stockOutLogs.isEmpty()) {
            productChangeLogRepository.saveAll(stockOutLogs);
        }
        return true;
    }

    /**
     * 취소/반품 확정분의 재고를 되돌린다 — 아이템 종결 전이(CANCELLED/RETURNED)와 같은 트랜잭션에서만 호출.
     *
     * <p>{@link #deduct}가 안에서 쓰는 보상 복원과 이름은 같아도 성질이 다르다 — 저쪽은 같은 트랜잭션에서
     * 방금 한 UPDATE를 되돌리는 것이고, 이쪽은 이미 커밋된 판매분을 되돌리는 것이다.
     *
     * <p><b>재입고 전환(0 → n)만 로그를 남긴다</b> — 주문에 의한 재고 증감은 order_item으로 복원되므로
     * 미기록이고 품절이 풀리는 순간만 신호다(02 D32). 차감의 품절 로그와 대칭이며, 이걸 빼면 I-15
     * 소비자가 "품절됨"만 듣고 "풀림"은 영영 못 듣는다.
     */
    @Transactional
    public void restore(Map<StockKey, Integer> quantities) {
        // 재입고 로그도 차감과 같은 이유로 모았다 한 번에 쓴다
        List<ProductChangeLog> restockLogs = new ArrayList<>();
        List<Map.Entry<StockKey, Integer>> ordered = quantities.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(LOCK_ORDER))
                .toList();

        for (Map.Entry<StockKey, Integer> entry : ordered) {
            StockKey key = entry.getKey();
            int qty = entry.getValue();
            if (productStockRepository.restore(key.productId(), key.optionId(), qty) == 0) {
                // 재고 행이 사라진 상품 — 되돌릴 곳이 없다. 취소·반품 자체는 유효하므로 막지 않는다
                log.warn("재고 복원 대상 행 없음 — 건너뜀 (productId={}, optionId={}, qty={})",
                        key.productId(), key.optionId(), qty);
                continue;
            }
            // 복원 결과가 복원량과 같으면 직전이 0이었다는 뜻 — 차감의 품절 판정을 뒤집은 것이다
            if (productStockRepository.findQuantity(key.productId(), key.optionId()).orElse(-1) == qty) {
                restockLogs.add(ProductChangeLog.ofOption(key.productId(), key.optionId(),
                        ProductChangeType.STOCK, "0", String.valueOf(qty)));
            }
        }
        if (!restockLogs.isEmpty()) {
            productChangeLogRepository.saveAll(restockLogs);
        }
    }
}
