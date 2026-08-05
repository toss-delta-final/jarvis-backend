package com.jarvis.product;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 상품 이미지 URL 조립 (02 D42) — DB엔 key만 있고 호스트는 설정에서 붙는다 */
class ImagePropertiesTest {

    private static final String KEY = "products/9276734652/detail/000.jpg";

    @Test
    @DisplayName("호스트 + key로 완성된 URL을 만든다")
    void buildsUrl() {
        ImageProperties props = new ImageProperties("https://cdn.example.com");
        assertThat(props.toUrl(KEY)).isEqualTo("https://cdn.example.com/" + KEY);
    }

    @Test
    @DisplayName("base-url 끝의 슬래시가 있든 없든 결과가 같다 — // 가 생기지 않는다")
    void normalizesTrailingSlash() {
        // 배포에서 주입하는 값이라 끝 슬래시 유무를 강제할 수 없다. 이 설정에서 가장 흔한 사고다
        assertThat(new ImageProperties("https://cdn.example.com/").toUrl(KEY))
                .isEqualTo(new ImageProperties("https://cdn.example.com").toUrl(KEY))
                .doesNotContain("com//");
    }

    @Test
    @DisplayName("key 앞에 슬래시가 붙어 와도 경로가 겹치지 않는다")
    void normalizesLeadingSlashOnKey() {
        assertThat(new ImageProperties("https://cdn.example.com").toUrl("/" + KEY))
                .isEqualTo("https://cdn.example.com/" + KEY);
    }
}
