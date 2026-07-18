-- Phase 4 시드 — M-9 데모용 문의 (06 Phase 4: 답변 완료 문의는 시드로 — 접수는 LLM internal 콜백이 Phase 5)
-- 대상: user@jarvis.shop (가입 API로 먼저 생성돼 있어야 함 — 없으면 아무것도 안 넣는다)
-- 적용: docker exec -i jarvis-mariadb mariadb -ujarvis -pjarvis-local jarvis < scripts/seed-phase4.sql

-- 답변 완료(DONE) 문의 — 마이페이지 답변 표시 데모용
INSERT INTO inquiry (member_id, title, content, status, answer, answered_by, answered_at, created_at)
SELECT m.id,
       '배송 지연 문의',
       '지난주에 주문한 상품이 아직 배송 준비 중입니다. 언제쯤 받을 수 있을까요?',
       'DONE',
       '안녕하세요, 고객님. 확인 결과 물류센터 출고가 지연되어 불편을 드렸습니다. 오늘 출고 완료되었으며 1~2일 내 수령 가능합니다.',
       (SELECT id FROM member WHERE email = 'seller@jarvis.shop'),
       NOW(),
       DATE_SUB(NOW(), INTERVAL 2 DAY)
FROM member m
WHERE m.email = 'user@jarvis.shop'
  AND NOT EXISTS (SELECT 1 FROM inquiry i WHERE i.member_id = m.id AND i.title = '배송 지연 문의');

-- 접수(PENDING) 문의 — 상태별 표시 데모용
INSERT INTO inquiry (member_id, title, content, status, created_at)
SELECT m.id,
       '반품 절차 문의',
       '수령한 상품의 색상이 화면과 달라 반품하고 싶습니다. 절차를 알려주세요.',
       'PENDING',
       DATE_SUB(NOW(), INTERVAL 1 DAY)
FROM member m
WHERE m.email = 'user@jarvis.shop'
  AND NOT EXISTS (SELECT 1 FROM inquiry i WHERE i.member_id = m.id AND i.title = '반품 절차 문의');
