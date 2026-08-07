package com.jarvis.product;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 변경의 단일 관문 (02 D33) — 조건부 차감·보상 복원·품절 로그를 한 트랜잭션으로 묶는다.
 * OrderStatusChanger가 상태 전이와 로그에 대해 하는 역할을 재고에 대해 한다.
 *
 * <p>조회 전용 {@link ProductService}와 나눠 둔 건 바뀌는 이유가 달라서다 — 저쪽은 "상품을 어떻게
 * 보여주나"(응답 모양·정렬)로 바뀌고 여기는 "재고를 어떻게 다루나"(차감 정책·로그 규칙)로 바뀐다.
 *
 * <p>호출자의 트랜잭션 안에서 동작한다(REQUIRED). 보상 복원은 같은 트랜잭션에서 조건부 UPDATE를
 * 되돌리는 것이지 커밋된 차감의 취소가 아니다 — 새 트랜잭션으로 떼면 그 성질이 깨진다.
 */
@Service
@RequiredArgsConstructor
public class ProductStockService {

    private final ProductRepository productRepository;
    private final ProductChangeLogRepository productChangeLogRepository;

    /**
     * 여러 상품의 재고를 함께 차감한다. 하나라도 부족하면 이미 차감한 분을 되돌리고 {@code false}.
     * 결제 성공(PAID 전이)과 같은 트랜잭션에서만 호출 — 부분 차감 상태로 커밋되면 안 된다.
     *
     * <p>productId 오름차순으로 잠근다 — 동시 주문이 서로 다른 순서로 같은 상품들을 잡으면 데드락이
     * 난다. 호출자가 정렬해 넘겨주길 기대하지 않고 여기서 보장한다(호출자가 늘면 놓치는 쪽이 생긴다).
     *
     * @param quantitiesByProduct productId → 차감 수량
     * @return 전 품목 차감 성공 여부. false면 재고가 호출 전 상태로 돌아가 있다
     */
    @Transactional
    public boolean deduct(Map<Long, Integer> quantitiesByProduct) {
        List<Map.Entry<Long, Integer>> applied = new ArrayList<>();
        // 품절 로그는 버퍼에 모았다가 전 품목 차감 성공 후에만 저장 — 일부 실패로 보상 복원되면
        // 허위 품절 로그가 남지 않도록 (02 D32·D33)
        List<ProductChangeLog> stockOutLogs = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : new TreeMap<>(quantitiesByProduct).entrySet()) {
            if (productRepository.deductStock(entry.getKey(), entry.getValue()) == 0) {
                applied.forEach(done -> productRepository.restoreStock(done.getKey(), done.getValue()));
                return false;
            }
            applied.add(entry);
            if (productRepository.findStockQuantity(entry.getKey()).orElse(-1) == 0) {
                // 주문에 의한 재고 -1은 미기록, 품절 전환(new_value=0)만 기록 (02 D32·D33)
                stockOutLogs.add(ProductChangeLog.of(entry.getKey(), ProductChangeType.STOCK,
                        String.valueOf(entry.getValue()), "0"));
            }
        }
        if (!stockOutLogs.isEmpty()) {
            productChangeLogRepository.saveAll(stockOutLogs);
        }
        return true;
    }
}
