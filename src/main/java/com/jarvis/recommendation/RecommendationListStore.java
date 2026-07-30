package com.jarvis.recommendation;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 추천 목록 영구 사본 쓰기 (02 D38). 트랜잭션 경계를 여기로 모아 Redis 쓰기와 분리한다. */
@Service
@RequiredArgsConstructor
public class RecommendationListStore {

    private final RecommendationListRepository listRepository;
    private final RecommendationListItemRepository itemRepository;

    /**
     * 한 콜백의 목록 전부를 한 트랜잭션으로 넣는다 — 반쪽만 저장되면 FastAPI가 발행할
     * products.ready가 사본 없는 목록을 가리키게 되고, 그 목록의 이벤트는 귀속 검증을 못 한다.
     * 멱등: 이미 있는 listId는 건너뛴다(노션 I-21 — 같은 (requestId, listId) 재전송은 200).
     */
    @Transactional
    public void saveAll(List<RecommendationList> lists, List<RecommendationListItem> items) {
        List<RecommendationList> fresh = lists.stream()
                .filter(list -> !listRepository.existsByListId(list.getListId()))
                .toList();
        if (fresh.isEmpty()) {
            return;
        }
        Set<String> freshIds = fresh.stream()
                .map(RecommendationList::getListId)
                .collect(Collectors.toSet());
        listRepository.saveAll(fresh);
        itemRepository.saveAll(items.stream()
                .filter(item -> freshIds.contains(item.getListId()))
                .toList());
    }
}
