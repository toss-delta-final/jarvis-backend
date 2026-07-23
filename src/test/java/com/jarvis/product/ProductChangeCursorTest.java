package com.jarvis.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** I-17 커서 코덱 — Base64URL (updatedAt, id) 왕복·폴백 (05 §I-17, 2026-07-22 LLM 합의) */
class ProductChangeCursorTest {

    @Test
    @DisplayName("encode→decode 왕복이 (updatedAt, id)를 보존한다")
    void roundTrip() {
        LocalDateTime updatedAt = LocalDateTime.of(2026, 1, 30, 12, 34, 56);

        ProductChangeCursor decoded = ProductChangeCursor.decode(
                ProductChangeCursor.encode(updatedAt, 10293L));

        assertThat(decoded.updatedAt()).isEqualTo(updatedAt);
        assertThat(decoded.id()).isEqualTo(10293L);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "  ", "0"})
    @DisplayName("since가 null·빈값·\"0\"이면 커서 없음(처음부터) → null")
    void noCursor(String since) {
        assertThat(ProductChangeCursor.decode(since)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-base64-!!!", "Zm9vYmFy", "MjAyNi0wMS0zMHwx"})
    @DisplayName("해석 불가·형식 위반 커서는 400 INVALID_CURSOR")
    void invalidCursor(String since) {
        // "Zm9vYmFy"=foobar(구분자 없음), "MjAyNi0wMS0zMHwx"=2026-01-30|1(날짜 파싱 실패)
        assertThatThrownBy(() -> ProductChangeCursor.decode(since))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_CURSOR);
    }
}
