-- 게스트 생명주기 재설계 (GUEST-LIFECYCLE) — guest 테이블에 은퇴 시각 추가.
--
-- 배경: 게스트를 "30일짜리 브라우저 주민증"에서 "로그인과 로그인 사이의 익명 구간(쿠키 2시간
-- sliding)"으로 바꾸면서, guest 테이블이 곧 "구간 → 귀속 계정" 연결 기록 대장이 된다.
-- behavior_events·recommendation_list는 고쳐 쓰지 않고 이 매핑으로 회원과 잇는다(백필 폐기).
--
-- 안전한 마이그레이션이다 — NULL 허용 컬럼 추가뿐이라 기존 행·앱 배포 순서와 무관하다.
-- 기존 행의 converted_at은 NULL로 남는다: 백필 시절 승계된 게스트라 정확한 은퇴 시각을 알 수 없고,
-- 추측값을 넣으면 "구간 경계"를 로그와 대조할 때 오히려 틀린 근거가 된다.

ALTER TABLE guest
    ADD COLUMN converted_at DATETIME NULL COMMENT '은퇴 시각(귀속 시점)' AFTER converted_member_id;
