package com.jarvis.global.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenHasherTest {

    @Test
    @DisplayName("SHA-256 hex — 알려진 벡터와 일치하고 길이는 64")
    void sha256Hex_knownVector() {
        assertThat(TokenHasher.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
                .hasSize(64);
    }

    @Test
    @DisplayName("같은 입력은 같은 해시, 다른 입력은 다른 해시")
    void sha256Hex_deterministic() {
        assertThat(TokenHasher.sha256Hex("token-a")).isEqualTo(TokenHasher.sha256Hex("token-a"));
        assertThat(TokenHasher.sha256Hex("token-a")).isNotEqualTo(TokenHasher.sha256Hex("token-b"));
    }
}
