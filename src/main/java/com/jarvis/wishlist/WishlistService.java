package com.jarvis.wishlist;

import com.jarvis.global.response.BusinessException;
import com.jarvis.global.response.ErrorCode;
import com.jarvis.product.ProductRepository;
import com.jarvis.product.ProductService;
import com.jarvis.product.dto.ProductCardResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M-4~6 (04 §5) — 찜 이벤트 적재 없음(E-1 8종 미포함).
 * 목록은 카드 공통 모양 — HIDDEN도 유지(purchasable=false), 장바구니(C-1)와 동일 원칙.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WishlistService {

    private final WishlistRepository wishlistRepository;
    private final ProductRepository productRepository;
    private final ProductService productService;

    public List<ProductCardResponse> getList(Long memberId) {
        List<Long> productIds = wishlistRepository.findAllByMemberIdOrderByIdDesc(memberId).stream()
                .map(Wishlist::getProductId)
                .toList();
        return productService.getCardsByIds(productIds);
    }

    @Transactional
    public void add(Long memberId, Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND));
        if (wishlistRepository.existsByMemberIdAndProductId(memberId, productId)) {
            throw new BusinessException(ErrorCode.WISHLIST_DUPLICATE);
        }
        wishlistRepository.save(Wishlist.of(memberId, productId));
    }

    @Transactional
    public void remove(Long memberId, Long productId) {
        Wishlist wishlist = wishlistRepository.findByMemberIdAndProductId(memberId, productId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WISHLIST_NOT_FOUND));
        wishlistRepository.delete(wishlist);
    }
}
