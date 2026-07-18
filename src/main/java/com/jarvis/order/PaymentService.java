package com.jarvis.order;

/**
 * 결제 판정 격리 (01 D7) — 실 PG(토스페이먼츠 테스트 모드 등) 전환 시 이 인터페이스의 구현체 교체가 유일한 변경 지점.
 */
public interface PaymentService {

    PaymentResult pay(String paymentMethod, int amount);

    record PaymentResult(boolean success, String failureCode) {

        public static PaymentResult approved() {
            return new PaymentResult(true, null);
        }

        public static PaymentResult declined(String failureCode) {
            return new PaymentResult(false, failureCode);
        }
    }
}
