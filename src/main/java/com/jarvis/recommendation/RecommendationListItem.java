package com.jarvis.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 목록에 담긴 상품 1건 (02 D38) — 상품을 JSON 배열 한 칸이 아니라 행으로 펼쳤다.
 * E-1 ③.5가 이벤트 1건마다 "이 productId가 그 목록에 있나, 몇 번째인가"를 묻기 때문이다.
 * 직접 할당 PK라 Persistable로 신규 여부를 알려 save() 시 불필요한 SELECT를 막는다(Guest와 같은 이유).
 */
@Entity
@Table(name = "recommendation_list_item")
@IdClass(RecommendationListItem.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecommendationListItem implements Persistable<RecommendationListItem.Key> {

    @Id
    @Column(name = "list_id", nullable = false, length = 64)
    private String listId;

    /** 0-based. 리랭킹 순서 = 렌더 순서 = 이벤트의 순위 */
    @Id
    @Column(nullable = false)
    private int position;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    /** 부모 목록의 값을 그대로 받는다 — 둘은 같은 트랜잭션에서 태어나므로 시각이 갈릴 이유가 없다 (02 D45) */
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private LocalDateTime createdAt;

    @Transient
    private boolean isNew = false;

    public static RecommendationListItem of(String listId, int position, Long productId,
                                            LocalDateTime createdAt) {
        RecommendationListItem item = new RecommendationListItem();
        item.listId = listId;
        item.position = position;
        item.productId = productId;
        item.createdAt = createdAt;
        item.isNew = true;
        return item;
    }

    @Override
    public Key getId() {
        return new Key(listId, position);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    /** 복합 PK — 이 repo 최초다. @IdClass는 no-arg 생성자를 요구해 record로 둘 수 없다. */
    @Getter
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        private String listId;
        private int position;

        public Key(String listId, int position) {
            this.listId = listId;
            this.position = position;
        }
    }
}
