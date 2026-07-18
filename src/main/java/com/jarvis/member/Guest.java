package com.jarvis.member;

import com.jarvis.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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

    @Transient
    private boolean isNew = false;

    public static Guest issue(String uuid) {
        Guest guest = new Guest();
        guest.id = uuid;
        guest.isNew = true;
        return guest;
    }

    /** 가입/로그인 승계 시 기록 (02 D5) — 이미 승계된 게스트는 덮어쓰지 않는다 */
    public void convertTo(Long memberId) {
        if (this.convertedMemberId == null) {
            this.convertedMemberId = memberId;
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
