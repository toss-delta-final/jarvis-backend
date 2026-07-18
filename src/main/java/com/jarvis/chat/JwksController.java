package com.jarvis.chat;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * JWKS 엔드포인트 (03 D5) — FastAPI가 티켓 검증용 public key를 조회(캐싱 + kid miss 시 refetch).
 * RFC 7517 표준 형식이라 envelope(ApiResponse) 미적용 — 외부 표준 클라이언트가 소비하는 유일한 예외.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final StreamTicketProvider streamTicketProvider;

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return streamTicketProvider.jwks();
    }
}
