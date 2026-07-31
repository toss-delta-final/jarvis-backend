package com.jarvis.member;

import com.jarvis.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 게스트 식별 (02 D5) — PK는 쿠키의 UUID 그대로.
 * 직접 할당 PK라 Persistable로 신규 여부를 알려 save() 시 불필요한 SELECT를 막는다.
 */
@Entity
@Table(name = "guest")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Guest extends BaseTimeEntity implements Persistable<String> {

    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(name = "converted_member_id")
    private Long convertedMemberId;

    @Column(name = "converted_at")
    private LocalDateTime convertedAt;

    @Transient
    private boolean isNew = false;

    public static Guest issue(String uuid) {
        Guest guest = new Guest();
        guest.id = uuid;
        guest.isNew = true;
        return guest;
    }

    /**
     * 구간 귀속 기록 — 이 게스트 구간의 주인이 누구인지 남긴다(GUEST-LIFECYCLE).
     * 가입·로그인 어느 쪽이든 동일하게 기록하며, 기록과 함께 쿠키가 반납돼 그 구간은 끝난다.
     * 이미 귀속된 게스트를 덮어쓰지 않는 건 방어용 — 회전 설계에선 재사용될 일이 없다.
     */
    public void convertTo(Long memberId) {
        if (this.convertedMemberId == null) {
            this.convertedMemberId = memberId;
            this.convertedAt = LocalDateTime.now();
        }
    }

    public boolean isConverted() {
        return convertedMemberId != null;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
