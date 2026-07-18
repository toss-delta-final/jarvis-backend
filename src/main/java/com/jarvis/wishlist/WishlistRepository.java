package com.jarvis.wishlist;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    boolean existsByMemberIdAndProductId(Long memberId, Long productId);

    Optional<Wishlist> findByMemberIdAndProductId(Long memberId, Long productId);

    /** M-4 — 최근 찜 순 */
    List<Wishlist> findAllByMemberIdOrderByIdDesc(Long memberId);
}
