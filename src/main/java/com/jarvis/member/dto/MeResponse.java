package com.jarvis.member.dto;

import com.jarvis.global.response.StringId;
import com.jarvis.member.Member;
import com.jarvis.member.Role;

/** A-5 — FE 라우팅 가드용 (04) */
public record MeResponse(@StringId Long id, String email, String nickname, Role role) {

    public static MeResponse from(Member member) {
        return new MeResponse(member.getId(), member.getEmail(), member.getNickname(), member.getRole());
    }
}
