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

    /**
     * 이 게스트 구간의 주인이 그 회원인가 — 게스트 시절 만든 것을 로그인 후에도 자기 것으로 읽게 하는
     * 연결 규칙(GUEST-LIFECYCLE). 로그는 고쳐 쓰지 않으므로 소유 판정을 이 매핑으로 한다(CH-5 소유자 예외).
     */
    @Transactional(readOnly = true)
    public boolean isOwnedBy(String guestId, Long memberId) {
        if (guestId == null || memberId == null) {
            return false;
        }
        return guestRepository.findById(guestId)
                .map(guest -> memberId.equals(guest.getConvertedMemberId()))
                .orElse(false);
    }
}
