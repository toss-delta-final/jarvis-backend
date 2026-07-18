package com.jarvis.order;

import com.jarvis.order.dto.ClaimRow;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** 아이템당 활성(REQUESTED) 클레임 최대 1개 — DB unique 대신 서비스 검증 (01 §5) */
    boolean existsByOrderItemIdAndStatus(Long orderItemId, ClaimStatus status);

    /** 자동 승인 대상 — 신청 후 claim-approve-minutes 경과 (01 §6) */
    List<Claim> findAllByStatusAndCreatedAtLessThanEqual(ClaimStatus status, LocalDateTime threshold);

    /** O-6 — 내 취소·반품 내역 (orderNo 파생용으로 주문 id·created_at 동반) */
    @Query("""
            SELECT new com.jarvis.order.dto.ClaimRow(
                c.id, c.type, c.status, c.reason, c.createdAt, c.processedAt,
                i.id, i.productName, i.optionName, i.price, i.quantity,
                o.id, o.createdAt)
            FROM Claim c
            JOIN OrderItem i ON c.orderItemId = i.id
            JOIN Order o ON i.orderId = o.id
            WHERE o.memberId = :memberId
            ORDER BY c.id DESC
            """)
    Page<ClaimRow> findMyClaims(@Param("memberId") Long memberId, Pageable pageable);
}
