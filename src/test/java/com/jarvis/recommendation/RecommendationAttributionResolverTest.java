package com.jarvis.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jarvis.chat.ChatIdentity;
import com.jarvis.global.event.EventAttribution;
import com.jarvis.recommendation.dto.RecommendationContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** E-1 ③.5 추천 귀속 도출 (노션 E-1, 02 D38) */
@ExtendWith(MockitoExtension.class)
class RecommendationAttributionResolverTest {

    private static final String LIST_ID = "3f9a2c1e7b8d4e5fa0c6d1e97b3f8a24";
    private static final String REQUEST_ID = "a63be350-ec96-4f44-b3f9-c962b6673a68";

    @Mock RecommendationListRepository listRepository;
    @Mock RecommendationListItemRepository itemRepository;

    @InjectMocks RecommendationAttributionResolver resolver;

    private static RecommendationList list() {
        return RecommendationList.ofChat(LIST_ID, REQUEST_ID, RecommendationListType.PICK_ONE,
                "11111111-1111-1111-1111-111111111111", ChatIdentity.member(7L), null, null, 3,
                LocalDateTime.now());
    }

    private RecommendationAttributionResolver.Snapshot snapshotWithList() {
        when(listRepository.findByListIdIn(Set.of(LIST_ID))).thenReturn(List.of(list()));
        when(itemRepository.findByListIdIn(Set.of(LIST_ID))).thenReturn(List.of(
                RecommendationListItem.of(LIST_ID, 0, 101L, LocalDateTime.now()),
                RecommendationListItem.of(LIST_ID, 1, 205L, LocalDateTime.now()),
                RecommendationListItem.of(LIST_ID, 2, 552L, LocalDateTime.now())));
        return resolver.snapshot(List.of(LIST_ID, LIST_ID)); // 중복 listId는 한 번만 조회
    }

    @Test
    @DisplayName("귀속 — 상품 이벤트는 지면과 순위까지 서버가 도출한다")
    void resolvesSurfaceAndPosition() {
        var snapshot = snapshotWithList();

        EventAttribution attribution = resolver.resolve(snapshot, LIST_ID, 205L);

        assertThat(attribution.recommendationRequestId()).isEqualTo(REQUEST_ID);
        assertThat(attribution.listId()).isEqualTo(LIST_ID);
        assertThat(attribution.surface()).isEqualTo("CHAT");
        assertThat(attribution.position()).isEqualTo(1); // 0-based
    }

    // impression·dismiss는 목록 단위 이벤트 — 순위 없이 지면만 귀속한다
    @Test
    @DisplayName("귀속 — 목록 단위 이벤트(productId 없음)는 순위 없이 지면만 붙는다")
    void resolvesListLevelEvent() {
        var snapshot = snapshotWithList();

        EventAttribution attribution = resolver.resolve(snapshot, LIST_ID, null);

        assertThat(attribution.listId()).isEqualTo(LIST_ID);
        assertThat(attribution.surface()).isEqualTo("CHAT");
        assertThat(attribution.position()).isNull();
    }

    // FE가 보낸 문맥을 믿으면 CTR 조작이 가능하다 — 불일치는 문맥을 폐기한다
    @Test
    @DisplayName("귀속 — 상품이 그 목록에 없으면 폐기한다 (CTR 조작 차단)")
    void discardsWhenProductNotInList() {
        var snapshot = snapshotWithList();

        assertThat(resolver.resolve(snapshot, LIST_ID, 999L)).isEqualTo(EventAttribution.NONE);
    }

    @Test
    @DisplayName("귀속 — 목록 사본이 없으면 폐기한다 (오타·위조·삭제)")
    void discardsWhenListMissing() {
        when(listRepository.findByListIdIn(Set.of("gone"))).thenReturn(List.of());
        var snapshot = resolver.snapshot(List.of("gone"));

        assertThat(resolver.resolve(snapshot, "gone", 101L)).isEqualTo(EventAttribution.NONE);
        // 목록이 없으면 아이템 조회 자체를 하지 않는다
        verifyNoInteractions(itemRepository);
    }

    @Test
    @DisplayName("귀속 — listId가 없는 이벤트는 조회 없이 통과")
    void skipsWithoutListId() {
        assertThat(resolver.snapshot(List.of()))
                .isEqualTo(RecommendationAttributionResolver.Snapshot.EMPTY);
        assertThat(resolver.resolve(RecommendationAttributionResolver.Snapshot.EMPTY, null, 101L))
                .isEqualTo(EventAttribution.NONE);

        verifyNoInteractions(listRepository, itemRepository);
    }

    // ---- 전환 귀속 (C-2·I-2·O-1) — 이벤트 귀속과 규칙이 다르다: 소유자까지 검증한다 ----

    private static final String GUEST_ID = "22222222-2222-2222-2222-222222222222";

    private static RecommendationContext context() {
        // requestId는 일부러 목록과 다른 값 — 서버가 요청값이 아니라 목록 값을 쓰는지 보기 위해
        return new RecommendationContext("00000000-0000-0000-0000-000000000000", LIST_ID);
    }

    private static RecommendationList guestList() {
        return RecommendationList.ofChat(LIST_ID, REQUEST_ID, RecommendationListType.PICK_ONE,
                "11111111-1111-1111-1111-111111111111", ChatIdentity.guest(GUEST_ID), null, null, 3,
                LocalDateTime.now());
    }

    /** 신원 없이 저장된 목록 — 세션 만료 뒤 도착한 I-21 콜백이 이렇게 남는다 */
    private static RecommendationList anonymousList() {
        return RecommendationList.ofChat(LIST_ID, REQUEST_ID, RecommendationListType.PICK_ONE,
                null, null, null, null, 3, LocalDateTime.now());
    }

    @Test
    @DisplayName("전환 귀속 — 3규칙 통과. requestId는 요청값이 아니라 목록에 저장된 값을 쓴다")
    void resolvesConversionFromStoredList() {
        when(listRepository.findByListId(LIST_ID)).thenReturn(Optional.of(list()));
        when(itemRepository.existsByListIdAndProductId(LIST_ID, 205L)).thenReturn(true);

        ConversionAttribution attribution = resolver.resolveForConversion(context(), 205L, 7L, null);

        assertThat(attribution.listId()).isEqualTo(LIST_ID);
        assertThat(attribution.recommendationRequestId()).isEqualTo(REQUEST_ID);
        assertThat(attribution.isPresent()).isTrue();
    }

    @Test
    @DisplayName("전환 귀속 — 게스트 목록은 같은 guestId로 담을 때만 통과")
    void resolvesConversionForGuestOwner() {
        when(listRepository.findByListId(LIST_ID)).thenReturn(Optional.of(guestList()));
        when(itemRepository.existsByListIdAndProductId(LIST_ID, 205L)).thenReturn(true);

        assertThat(resolver.resolveForConversion(context(), 205L, null, GUEST_ID).isPresent()).isTrue();
    }

    // E-1과 갈리는 지점 — 이벤트는 소유자를 안 보지만 전환은 매출로 집계되므로 본다
    @Test
    @DisplayName("전환 귀속 — 남의 목록이면 폐기한다 (매출 도용 차단)")
    void discardsConversionWhenOwnerMismatch() {
        when(listRepository.findByListId(LIST_ID)).thenReturn(Optional.of(list()));

        assertThat(resolver.resolveForConversion(context(), 205L, 999L, null))
                .isEqualTo(ConversionAttribution.NONE);
        // 소유자에서 걸리면 상품 포함 여부는 묻지 않는다
        verifyNoInteractions(itemRepository);
    }

    @Test
    @DisplayName("전환 귀속 — 신원 없이 저장된 목록은 소유자를 확인할 수 없어 폐기한다")
    void discardsConversionForAnonymousList() {
        when(listRepository.findByListId(LIST_ID)).thenReturn(Optional.of(anonymousList()));

        assertThat(resolver.resolveForConversion(context(), 205L, 7L, null))
                .isEqualTo(ConversionAttribution.NONE);
    }

    @Test
    @DisplayName("전환 귀속 — 상품이 그 목록에 없으면 폐기한다 (FE 상태에 남은 이전 listId)")
    void discardsConversionWhenProductNotInList() {
        when(listRepository.findByListId(LIST_ID)).thenReturn(Optional.of(list()));
        when(itemRepository.existsByListIdAndProductId(LIST_ID, 999L)).thenReturn(false);

        assertThat(resolver.resolveForConversion(context(), 999L, 7L, null))
                .isEqualTo(ConversionAttribution.NONE);
    }

    @Test
    @DisplayName("전환 귀속 — 목록 사본이 없으면 폐기한다")
    void discardsConversionWhenListMissing() {
        when(listRepository.findByListId(LIST_ID)).thenReturn(Optional.empty());

        assertThat(resolver.resolveForConversion(context(), 205L, 7L, null))
                .isEqualTo(ConversionAttribution.NONE);
    }

    @Test
    @DisplayName("전환 귀속 — 추천 경유가 아니면 조회 없이 통과 (담기 대부분이 이 경로)")
    void skipsConversionWithoutContext() {
        assertThat(resolver.resolveForConversion(null, 205L, 7L, null))
                .isEqualTo(ConversionAttribution.NONE);
        assertThat(resolver.resolveForConversion(new RecommendationContext(REQUEST_ID, null), 205L, 7L, null))
                .isEqualTo(ConversionAttribution.NONE);

        verifyNoInteractions(listRepository, itemRepository);
    }
}
