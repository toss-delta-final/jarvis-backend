package com.jarvis.address;

import com.jarvis.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 배송지 (02 §3) — 기본 배송지 회원당 1개는 서비스 레이어가 보장 (02 D29).
 * 주문은 스냅샷 복사라 수정·삭제가 기존 주문에 영향 없음 (02 D1).
 */
@Entity
@Table(name = "address")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Address extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 50)
    private String label;

    @Column(nullable = false, length = 50)
    private String recipient;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "zip_code", nullable = false, length = 10)
    private String zipCode;

    @Column(nullable = false, length = 255)
    private String address1;

    @Column(length = 255)
    private String address2;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    public static Address create(Long memberId, String label, String recipient, String phone,
                                 String zipCode, String address1, String address2, boolean isDefault) {
        Address address = new Address();
        address.memberId = memberId;
        address.label = label;
        address.recipient = recipient;
        address.phone = phone;
        address.zipCode = zipCode;
        address.address1 = address1;
        address.address2 = address2;
        address.isDefault = isDefault;
        return address;
    }

    /** M-8 PATCH — null 필드는 유지 */
    public void update(String label, String recipient, String phone,
                       String zipCode, String address1, String address2) {
        if (label != null) this.label = label;
        if (recipient != null) this.recipient = recipient;
        if (phone != null) this.phone = phone;
        if (zipCode != null) this.zipCode = zipCode;
        if (address1 != null) this.address1 = address1;
        if (address2 != null) this.address2 = address2;
    }

    public void markDefault() {
        this.isDefault = true;
    }
}
