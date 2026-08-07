package com.jarvis.seller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** I-14 고객 라벨 (노션 I-14 2026-08-06 프라이버시 개정) */
class CustomerLabelerTest {

    private final CustomerLabeler labeler = new CustomerLabeler("test-label-secret");

    @Test
    @DisplayName("라벨 — 6자 Base32(A-Z·2-7)")
    void producesSixCharBase32() {
        String label = labeler.label(7L, 42L);

        assertThat(label).hasSize(6).matches("[A-Z2-7]{6}");
    }

    // 결정적이어야 "이 고객이 또 취소했다"는 반복 패턴을 표현할 수 있다
    @Test
    @DisplayName("라벨 — 같은 (브랜드, 회원)은 항상 같은 라벨 (사례번호로 쓰려면 결정적이어야 한다)")
    void isDeterministic() {
        assertThat(labeler.label(7L, 42L)).isEqualTo(labeler.label(7L, 42L));
    }

    // 브랜드가 같은 라벨을 보면 "우리 둘 다 겪은 그 고객"으로 대조 추적이 가능해진다
    @Test
    @DisplayName("라벨 — 같은 회원이라도 브랜드가 다르면 다른 라벨 (브랜드 간 대조 추적 차단)")
    void differsPerBrand() {
        assertThat(labeler.label(7L, 42L)).isNotEqualTo(labeler.label(8L, 42L));
    }

    @Test
    @DisplayName("라벨 — 다른 회원은 다른 라벨")
    void differsPerMember() {
        assertThat(labeler.label(7L, 42L)).isNotEqualTo(labeler.label(7L, 43L));
    }

    // secret이 유출되지 않는 한 라벨만으로는 memberId를 복원할 수 없다는 것의 최소 증거 —
    // 키가 다르면 같은 입력이 전혀 다른 라벨이 된다
    @Test
    @DisplayName("라벨 — secret이 다르면 같은 입력도 다른 라벨 (역산 불가의 근거)")
    void dependsOnSecret() {
        assertThat(labeler.label(7L, 42L))
                .isNotEqualTo(new CustomerLabeler("other-secret").label(7L, 42L));
    }

    @Test
    @DisplayName("라벨 — memberId가 없으면 null (라벨링할 대상이 없다)")
    void nullMemberHasNoLabel() {
        assertThat(labeler.label(7L, null)).isNull();
    }
}
