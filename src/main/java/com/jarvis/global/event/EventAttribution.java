package com.jarvis.global.event;

/**
 * 이벤트의 추천 귀속 (02 D38, 노션 E-1 ③.5) — **FE가 보낸 값이 아니라 서버가 도출한 것**.
 * FE가 준 문맥을 그대로 믿으면 CTR을 마음대로 부풀릴 수 있어, listId로 목록을 조회해
 * requestId·지면·순위를 서버가 채운다. 귀속에 실패하면 문맥만 버리고 이벤트 본체는 남긴다.
 */
public record EventAttribution(String recommendationRequestId, String listId, String surface,
                               Integer position) {

    public static final EventAttribution NONE = new EventAttribution(null, null, null, null);
}
