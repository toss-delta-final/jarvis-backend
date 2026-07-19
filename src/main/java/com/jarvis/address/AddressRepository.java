package com.jarvis.address;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AddressRepository extends JpaRepository<Address, Long> {

    /** M-8 목록 — 기본 먼저, 이후 등록순 */
    List<Address> findAllByMemberIdOrderByIsDefaultDescIdAsc(Long memberId);

    /** 소유권 검증 겸 단건 조회 — 남의 주소는 404 (04 §5) */
    Optional<Address> findByIdAndMemberId(Long id, Long memberId);

    /**
     * 기본 해제 — 조건부 UPDATE로 다건도 안전하게 내린다. 단건 Optional 조회는 금지:
     * 경합으로 기본이 2건이 되면 IncorrectResultSizeDataAccessException → 그 회원의 배송지 API가
     * 영구 500이 된다(2026-07-18 멱등성 검토). uk_address_default가 새 발생을 막고, 이 쿼리가 기존 상태를 치유.
     */
    @Modifying(flushAutomatically = true)
    @Query("update Address a set a.isDefault = false where a.memberId = :memberId and a.isDefault = true")
    int clearDefault(@Param("memberId") Long memberId);

    /** 기본 삭제 시 등록순 승격 후보 (02 D29) */
    Optional<Address> findFirstByMemberIdAndIdNotOrderByIdAsc(Long memberId, Long excludedId);

    boolean existsByMemberId(Long memberId);

    long countByMemberId(Long memberId);
}
