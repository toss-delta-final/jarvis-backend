package com.jarvis.member;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게스트 발급 (03 D3) — 쿠키 세팅 + guest 행 INSERT가 한 동작. 발급 입구(채팅 CH-1·담기 C-2·
 * 이벤트 E-1) 공용 단일 규칙 — 호출측 @Transactional에 참여(REQUIRED)해 담기 트랜잭션 안에서도 동작.
 */
@Service
@RequiredArgsConstructor
public class GuestService {

    private final GuestRepository guestRepository;

    /** 쿠키/행 부재 시 발급·복구 — 반환값이 있으면 컨트롤러가 guest_id 쿠키를 새로 내린다 */
    @Transactional
    public String ensureGuest(String guestId) {
        if (guestId != null && guestRepository.existsById(guestId)) {
            return null;
        }
        String id = guestId != null ? guestId : UUID.randomUUID().toString();
        guestRepository.save(Guest.issue(id));
        return id;
    }
}
