package com.jarvis.address;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {

    /** M-8 목록 — 기본 먼저, 이후 등록순 */
    List<Address> findAllByMemberIdOrderByIsDefaultDescIdAsc(Long memberId);

    /** 소유권 검증 겸 단건 조회 — 남의 주소는 404 (04 §5) */
    Optional<Address> findByIdAndMemberId(Long id, Long memberId);

    Optional<Address> findByMemberIdAndIsDefaultTrue(Long memberId);

    /** 기본 삭제 시 등록순 승격 후보 (02 D29) */
    Optional<Address> findFirstByMemberIdAndIdNotOrderByIdAsc(Long memberId, Long excludedId);

    boolean existsByMemberId(Long memberId);

    long countByMemberId(Long memberId);
}
