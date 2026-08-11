package com.jarvis.cart;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findAllByMemberIdOrderByIdDesc(Long memberId);

    List<CartItem> findAllByGuestIdOrderByIdDesc(String guestId);

    List<CartItem> findAllByGuestId(String guestId);

    List<CartItem> findAllByMemberId(Long memberId);

    /**
     * optionId NULL(무옵션)을 IS NULL로 매칭 — 파생 쿼리는 null 파라미터를 = NULL로 만들어 불일치.
     * List 반환 + id 오름차순: 단건 기대(Optional)면 중복 행이 있을 때 그 회원의 장바구니가 영구 500이 된다.
     * 중복 자체는 2026-08-11부터 DB가 막지만(02 D44 — uk가 가상 컬럼 option_key를 쓴다), 그 이전에
     * 생긴 행이 남아 있을 수 있어 서비스가 첫 행으로 병합·자가치유하는 구조를 유지한다.
     */
    @Query("""
            SELECT c FROM CartItem c
            WHERE c.memberId = :memberId AND c.productId = :productId
              AND ((:optionId IS NULL AND c.optionId IS NULL) OR c.optionId = :optionId)
            ORDER BY c.id ASC
            """)
    List<CartItem> findMemberLines(@Param("memberId") Long memberId,
                                   @Param("productId") Long productId,
                                   @Param("optionId") Long optionId);

    @Query("""
            SELECT c FROM CartItem c
            WHERE c.guestId = :guestId AND c.productId = :productId
              AND ((:optionId IS NULL AND c.optionId IS NULL) OR c.optionId = :optionId)
            ORDER BY c.id ASC
            """)
    List<CartItem> findGuestLines(@Param("guestId") String guestId,
                                  @Param("productId") Long productId,
                                  @Param("optionId") Long optionId);

    /**
     * 합산 쓰기 경로용 잠금 조회 (C-2·I-2·병합) — 같은 라인의 동시 담기(유저 클릭 vs 챗봇 콜백)가
     * 읽고-더하고-쓰는 사이에 끼어들면 증가분 하나가 유실된다(lost update). PESSIMISTIC_WRITE로
     * consolidate → 상한 검사 → 합산을 직렬화한다(O-2 findByIdForUpdate와 동일 관례).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM CartItem c
            WHERE c.memberId = :memberId AND c.productId = :productId
              AND ((:optionId IS NULL AND c.optionId IS NULL) OR c.optionId = :optionId)
            ORDER BY c.id ASC
            """)
    List<CartItem> findMemberLinesForUpdate(@Param("memberId") Long memberId,
                                            @Param("productId") Long productId,
                                            @Param("optionId") Long optionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM CartItem c
            WHERE c.guestId = :guestId AND c.productId = :productId
              AND ((:optionId IS NULL AND c.optionId IS NULL) OR c.optionId = :optionId)
            ORDER BY c.id ASC
            """)
    List<CartItem> findGuestLinesForUpdate(@Param("guestId") String guestId,
                                           @Param("productId") Long productId,
                                           @Param("optionId") Long optionId);
}
