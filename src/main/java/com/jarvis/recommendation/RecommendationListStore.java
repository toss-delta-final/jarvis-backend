package com.jarvis.recommendation;

import java.util.List;
import java.util.Map;
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
     *
     * @return 이미 존재해 건너뛴 listId — 호출자가 로그로 드러낸다. 정상 재시도면 무해하지만,
     *         같은 listId에 다른 내용이 오는 계약 위반은 이 신호 없이는 조용히 묻힌다
     */
    @Transactional
    public List<String> saveAll(List<RecommendationList> lists, List<RecommendationListItem> items) {
        Map<Boolean, List<RecommendationList>> byExisting = lists.stream()
                .collect(Collectors.partitioningBy(
                        list -> listRepository.existsByListId(list.getListId())));
        List<RecommendationList> fresh = byExisting.get(false);
        List<String> skipped = byExisting.get(true).stream()
                .map(RecommendationList::getListId)
                .toList();
        if (fresh.isEmpty()) {
            return skipped;
        }
        Set<String> freshIds = fresh.stream()
                .map(RecommendationList::getListId)
                .collect(Collectors.toSet());
        listRepository.saveAll(fresh);
        itemRepository.saveAll(items.stream()
                .filter(item -> freshIds.contains(item.getListId()))
                .toList());
        return skipped;
    }
}
