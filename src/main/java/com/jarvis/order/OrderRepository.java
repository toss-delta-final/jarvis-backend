package com.jarvis.order;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findAllByMemberIdOrderByIdDesc(Long memberId, Pageable pageable);

    /**
     * O-2 재결제 진입점 전용 비관적 쓰기 락 — 동시 재결제(더블클릭) 직렬화.
     * 락 획득 후 상태를 재확인하므로 두 번째 요청은 이미 PAID로 바뀐 주문에서 재차감하지 못한다 (01 §2-1).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);
}
