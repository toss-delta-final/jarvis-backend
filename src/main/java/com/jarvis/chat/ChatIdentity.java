package com.jarvis.chat;

/** 티켓 sub/sub_type의 원천 (05 §1-0) — 서버가 검증해 채운 신원. 클라이언트 주장 아님 */
public record ChatIdentity(String subType, String sub) {

    public static final String TYPE_MEMBER = "member";
    public static final String TYPE_GUEST = "guest";

    public static ChatIdentity member(Long memberId) {
        return new ChatIdentity(TYPE_MEMBER, String.valueOf(memberId));
    }

    public static ChatIdentity guest(String guestId) {
        return new ChatIdentity(TYPE_GUEST, guestId);
    }
}
