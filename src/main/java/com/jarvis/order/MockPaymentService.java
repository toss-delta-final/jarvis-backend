package com.jarvis.order;

import org.springframework.stereotype.Component;

/**
 * 모의 결제 (01 D7·§2-1) — "테스트: 결제 실패"(MOCK_FAIL)만 무조건 실패, 그 외 수단은 무조건 성공.
 * 랜덤 실패는 데모를 망치므로 없음.
 */
@Component
public class MockPaymentService implements PaymentService {

    static final String FAIL_METHOD = "MOCK_FAIL";
    static final String FAIL_CODE = "MOCK_DECLINED";

    @Override
    public PaymentResult pay(String paymentMethod, int amount) {
        if (FAIL_METHOD.equals(paymentMethod)) {
            return PaymentResult.declined(FAIL_CODE);
        }
        return PaymentResult.approved();
    }
}
