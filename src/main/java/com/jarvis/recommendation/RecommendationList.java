package com.jarvis.recommendation;

import com.jarvis.chat.ChatIdentity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 추천 목록 영구 사본 (02 D38) — I-21(채팅)·I-22(홈) 공용.
 * Redis는 CH-5 조회 전용 10분 캐시고 이 테이블이 정본이다 — 늦게 도착한 행동 이벤트의
 * 추천 귀속 검증(E-1 ③.5)과 "그때 무엇을 추천했는지" 재현이 캐시만으로는 불가능하다.
 * reasons 텍스트는 담지 않는다(표시 전용, 평가용 원본은 FastAPI 보관 — 노션 I-21).
 */
@Entity
@Table(name = "recommendation_list")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationList {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "list_id", nullable = false, length = 64)
    private String listId;

    @Column(name = "recommendation_request_id", nullable = false, columnDefinition = "char(36)")
    private String recommendationRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RecommendationSurface surface;

    @Enumerated(EnumType.STRING)
    @Column(name = "list_type", nullable = false, length = 10)
    private RecommendationListType listType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecommendationSource source;

    @Column(name = "session_id", columnDefinition = "char(36)")
    private String sessionId;

    @Column(name = "member_id")
    private Long memberId;

    @Column(name = "guest_id", columnDefinition = "char(36)")
    private String guestId;

    @Column(length = 50)
    private String label;

    @Column(name = "total_budget")
    private Integer totalBudget;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    /**
     * I-21 콜백 1건 = 목록 1개. 채팅은 지면이 CHAT으로 고정이고 source도 항상 AI_RECOMMENDED다
     * (인기 fallback은 홈 전용 — 노션 P-5).
     * identity가 null인 건 정상값이다 — 세션이 이미 만료된 뒤 도착한 콜백은 신원을 구할 수 없고,
     * 그때도 저장하되(익명 저장) CH-5에서는 조회되지 않는다(노션 I-21 「실패가 아닌 것」).
     */
    /**
     * P-5(홈) 목록 — I-22 응답이든 P-4 인기상품 대체든 한 행으로 남긴다(노션 P-5·I-22).
     * 홈은 세션이 없어 {@code sessionId}가 null이고, 게스트는 이 API를 쓰지 않아 항상 회원이다.
     *
     * <p>{@code listType}은 {@code PICK_ONE} 고정이다 — 홈 카드는 서로 대안이지 세트가 아니다.
     * {@code source}로 "AI가 뽑았나, 인기상품으로 대신했나"가 갈리며, 이 값이 곧 P-5 응답의
     * {@code source}이고 AI 추천 성과 집계에서 대체분을 배제하는 근거다.
     */
    public static RecommendationList ofHome(String listId, String recommendationRequestId,
                                            Long memberId, RecommendationSource source,
                                            int itemCount, LocalDateTime createdAt) {
        RecommendationList list = new RecommendationList();
        list.listId = listId;
        list.recommendationRequestId = recommendationRequestId;
        list.surface = RecommendationSurface.HOME;
        list.listType = RecommendationListType.PICK_ONE;
        list.source = source;
        list.memberId = memberId;
        list.itemCount = itemCount;
        list.createdAt = createdAt;
        return list;
    }

    public static RecommendationList ofChat(String listId, String recommendationRequestId,
                                            RecommendationListType listType, String sessionId,
                                            ChatIdentity identity, String label, Integer totalBudget,
                                            int itemCount, LocalDateTime createdAt) {
        RecommendationList list = new RecommendationList();
        list.listId = listId;
        list.recommendationRequestId = recommendationRequestId;
        list.surface = RecommendationSurface.CHAT;
        list.listType = listType;
        list.source = RecommendationSource.AI_RECOMMENDED;
        list.sessionId = sessionId;
        boolean member = identity != null && ChatIdentity.TYPE_MEMBER.equals(identity.subType());
        list.memberId = member ? Long.valueOf(identity.sub()) : null;
        list.guestId = identity != null && !member ? identity.sub() : null;
        list.label = label;
        list.totalBudget = totalBudget;
        list.itemCount = itemCount;
        list.createdAt = createdAt;
        return list;
    }
}
