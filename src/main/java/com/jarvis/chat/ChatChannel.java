package com.jarvis.chat;

/**
 * CH-1 채널 (04 §6) — SELLER는 S-4 별도 입구 전용(CH-1로는 발급 불가, Phase 6).
 * CS는 2026-08-11 제거 — 문의 챗봇(CH-3) 폐기(2026-08-07)로 용도가 사라진 유령 어휘였다(노션 CH-1 개정).
 * "CS"가 와이어로 오면 enum 역직렬화 실패 → 400 VALIDATION_ERROR (SELLER를 CH-1에서 막는 것과 동일 결과).
 */
public enum ChatChannel {
    SHOPPING, SELLER
}
