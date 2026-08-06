package com.jarvis.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.cart.dto.CartAddRequest;
import com.jarvis.cart.dto.CartResponse;
import com.jarvis.internal.dto.InternalCartResponse;
import com.jarvis.product.dto.ProductCardResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * id 문자열 직렬화 (2026-08-06) — FE가 JS 안전 정수(9,007,199,254,740,991)를 넘는 id를 숫자로
 * 받으면 값이 뭉개진다. 경계를 넘는 값으로 왕복을 검증해 계약이 되돌아가는 걸 막는다.
 */
class StringIdSerializationTest {

    /** MAX_SAFE_INTEGER + 2 — 숫자로 내보내면 JS가 ...992로 뭉개는 값 */
    private static final long UNSAFE_ID = 9_007_199_254_740_993L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static CartResponse.Item cartItem(Long optionId) {
        return new CartResponse.Item(UNSAFE_ID, UNSAFE_ID + 1, "무선 이어폰",
                UNSAFE_ID + 2, "브랜드", optionId, "화이트",
                1, 10000, 12000, "https://img", "AVAILABLE", 99);
    }

    @Test
    @DisplayName("공개 응답의 id는 문자열로 나가고 큰 값도 정확히 보존된다")
    void publicIdsSerializeAsString() throws Exception {
        String json = objectMapper.writeValueAsString(CartResponse.of(List.of(cartItem(42L))));

        assertThat(json).contains("\"cartItemId\":\"9007199254740993\"")
                .contains("\"productId\":\"9007199254740994\"")
                .contains("\"brandId\":\"9007199254740995\"")
                .contains("\"optionId\":\"42\"");
    }

    @Test
    @DisplayName("옵션 없는 항목의 optionId는 문자열 \"null\"이 아니라 null")
    void nullIdStaysNull() throws Exception {
        String json = objectMapper.writeValueAsString(CartResponse.of(List.of(cartItem(null))));

        assertThat(json).contains("\"optionId\":null");
    }

    @Test
    @DisplayName("id가 아닌 집계 수치는 숫자로 남는다")
    void countsStayNumeric() throws Exception {
        ProductCardResponse card = new ProductCardResponse(UNSAFE_ID, "상품", "브랜드",
                10000, 12000, "https://img", 4.5, 120L, "AVAILABLE");

        String json = objectMapper.writeValueAsString(card);

        assertThat(json).contains("\"productId\":\"9007199254740993\"")
                .contains("\"reviewCount\":120");
    }

    @Test
    @DisplayName("내부(LLM) 응답의 id는 숫자를 유지한다 — Python은 정밀도 손실이 없다")
    void internalIdsStayNumeric() throws Exception {
        String json = objectMapper.writeValueAsString(
                InternalCartResponse.from(CartResponse.of(List.of(cartItem(42L)))));

        assertThat(json).contains("\"cartItemId\":9007199254740993")
                .contains("\"optionId\":42");
    }

    @Test
    @DisplayName("요청은 문자열 id를 그대로 받는다 — FE가 받은 값을 되보내도 깨지지 않는다")
    void requestAcceptsStringId() throws Exception {
        CartAddRequest request = objectMapper.readValue(
                "{\"productId\":\"9007199254740993\",\"optionId\":\"42\",\"quantity\":1}",
                CartAddRequest.class);

        assertThat(request.productId()).isEqualTo(UNSAFE_ID);
        assertThat(request.optionId()).isEqualTo(42L);
    }
}
