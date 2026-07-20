# Spring BE 로컬 통합 가이드

> 기준: 2026-07-21 현재 워크트리. Spring은 커머스 원본 데이터와 인증의 권위 서버이며 FE와 AI 사이의 세션·추천 목록 계약을 연결한다.

## 1. BE가 맡는 역할

- 회원, 상품, 가격, 재고, 장바구니, 주문 등 커머스 원본은 MariaDB/Spring이 소유한다.
- FE 로그인 access token과 HttpOnly refresh cookie를 발급한다.
- 채팅 세션과 FastAPI 전용 RS256 stream ticket을 발급한다.
- AI의 검색·장바구니·구매이력·추천목록 push 요청을 `/internal/**`로 제공한다.
- AI가 추천한 product ID 목록을 받아 Redis에 저장하고, FE에는 최신 상품 카드로 돌려준다.

```text
FE :3000 ── public /api/** ──> Spring :8080
AI :8000 ── X-Internal-Token + /internal/** ──> Spring :8080
Spring :8080 ── session-end + X-Internal-Token ──> AI :8000
Spring ── MariaDB :3306 / Redis :6379
```

## 2. 이번에 맞춘 핵심 충돌

### 2.1 로컬 셋업을 한 번에 재현 가능하게 변경

`scripts/setup-local.sh`가 다음 작업을 순서대로 수행한다.

1. MariaDB/Redis Docker 컨테이너 기동 및 health 대기
2. 스키마가 없으면 `docs/backend/schema.sql` 적용
3. 기존 phase seed와 `scripts/seed-sample-100.sql` 적용
4. `application-local.yml`이 없으면 JWT secret, RSA stream key, internal token 생성
5. AI 주소를 `http://localhost:8000`으로 설정

`scripts/start-backend.sh`는 workspace JDK, 시스템 Java, Windows JDK 순으로 JDK 21을 탐색한 뒤 `bootRun`을 실행한다.

### 2.2 sample_100을 MariaDB 계약으로 변환

추가된 `scripts/generate-sample-100-sql.py`는 `../sample_100`을 읽어 `scripts/seed-sample-100.sql`을 생성한다.

- 상품 JSON과 AI 문서의 product ID 집합이 정확히 100건인지 검증
- 13개 root domain과 87개 경로형 하위 category 생성
- 브랜드, 상품, 속성 JSON, 설명, 이미지 URL, 옵션 적재
- AI pg-catalog와 동일한 원본 `product_id` 사용
- `INSERT ... ON DUPLICATE KEY UPDATE`로 재실행 가능
- 상품 옵션은 해당 샘플 ID 범위만 삭제 후 재생성해 중복 방지

원본 번들이 바뀌었을 때만 다시 생성한다.

```bash
cd jarvis-backend
python3 scripts/generate-sample-100-sql.py
```

### 2.3 AI가 만든 카테고리 표현을 Spring 검색에 연결

샘플 DB에는 충돌 방지를 위해 `여성의류 > 청바지` 같은 경로형 category가 저장된다. 반면 AI는 `청바지` 또는 `부츠컷 청바지`를 보낼 수 있다.

`CategoryService.resolveIdsByName`은 다음 순서로 category ID를 찾도록 바뀌었다.

1. 전체 문자열 exact match
2. `" > 마지막구간"` suffix match
3. 공백 앞 수식어를 하나씩 제거하고 재시도

따라서 `부츠컷 청바지`도 실제 `... > 청바지` category로 연결된다. 동작은 `CategoryServiceTest`에 회귀 테스트로 고정했다.

### 2.4 서비스 간 internal token을 하드코딩에서 분리

`application.yml`의 internal token 하드코딩을 제거하고 `${INTERNAL_TOKEN:}` 또는 local yml의 `app.internal.token`을 사용한다.

- AI → Spring `/internal/**`: `X-Internal-Token` 필수
- Spring → AI `/events/session-end`: 동일 token 사용
- token이 비어 있으면 Spring의 `/internal/**` filter가 fail-closed 401을 반환한다.

AI `.env`의 `INTERNAL_API_TOKEN`에는 `application-local.yml`의 `app.internal.token`과 **동일한 값**을 넣어야 한다. 값 자체는 문서나 Git에 기록하지 않는다.

### 2.5 session-end 이벤트 계약 보완

새 대화를 시작하거나 세션을 종료할 때 Spring이 AI로 보내는 payload를 실제 FastAPI 스키마에 맞췄다.

```json
{
  "eventId": "{sessionId}:{reason}",
  "sessionId": "...",
  "userId": "...",
  "reason": "NEW_CONVERSATION"
}
```

요청에는 `X-Internal-Token`을 붙인다. 기존 payload에 없던 `eventId`, `userId`가 추가되어 AI의 멱등 처리와 회원 프로필 적재가 가능해졌다.

관련 파일: `ChatSessionService.java`, `LlmNotifyClient.java`, 각 테스트.

### 2.6 로컬 CORS와 포트 계약 통일

local profile에서 다음 FE origin을 credential 포함 요청 대상으로 허용한다.

- 현재 고정 포트: `http://localhost:3000`
- 구 Vite 기본 포트 호환: `http://localhost:5173`

## 3. 채팅 연결에서 Spring이 제공하는 계약

### 세션/티켓

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/api/chat/sessions` | 구매자/게스트 세션과 stream ticket 발급 |
| POST | `/api/chat/seller/sessions` | 판매자 세션과 brand-scoped ticket 발급 |
| POST | `/api/chat/tickets` | 만료된 stream ticket 재발급 |
| GET | `/.well-known/jwks.json` | FastAPI가 RS256 서명을 검증할 공개키 |

Spring이 내려주는 `llmSseUrl`은 구매자면 `http://localhost:8000/chat`, 판매자면 `http://localhost:8000/seller/chat`이다.

stream ticket 기본 계약:

- `iss=jarvis-spring-auth`
- `aud=jarvis-fastapi-ai`
- `scope=chat:stream`
- 기본 TTL 60초
- `sub`, `sub_type`; 판매자는 `role=seller`, `brandId` 추가

### 추천 목록 경로 B

1. AI가 `POST /internal/recommendations`로 `sessionId`, `listId`, `productIds` push
2. Spring이 Redis에 TTL 저장
3. AI가 FE에 `products.ready {sessionId, listId}` SSE emit
4. FE가 `GET /api/chat/lists/{listId}` 호출
5. Spring이 MariaDB 기준 최신 카드 정보를 반환

## 4. 로컬 실행

```bash
cd jarvis-backend
bash scripts/setup-local.sh
bash scripts/start-backend.sh
```

확인:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/categories
curl http://localhost:8080/api/products/popular
```

`application-local.yml`은 머신별 secret을 가지므로 Git에 커밋하지 않는다.

## 5. 세 서버 스모크 테스트

FE, Spring, FastAPI가 모두 기동된 뒤:

```bash
cd jarvis-backend
bash scripts/smoke-integration.sh
```

이 스크립트는 다음을 검증한다.

- 세 서버 health
- FE 3000/5173 → Spring CORS
- Spring `ApiResponse` envelope
- 로그인 및 HttpOnly cookie refresh
- 인증 장바구니
- Spring 세션/RS256 ticket → FastAPI SSE
- 판매자 API

실제 LLM provider를 사용하므로 AI 키와 모델 설정이 유효해야 하며 호출 비용이 발생할 수 있다.

## 6. 현재 확인된 제한

- category fallback은 suffix와 공백 수식어 제거 규칙이다. 동의어/형태소 검색 엔진은 아니다.
- 추천 후보 검색은 현재 Spring의 부분 문자열 keyword 검색과 category/price/brand/size 정형 필터가 기준이며 semantic vector 검색은 아니다.
- 샘플 100건은 전량 `ON_SALE`, stock 100으로 넣기 때문에 품절 시나리오는 별도 seed가 필요하다.
- `GET /api/chat/lists/{listId}`는 현재 FE 카드 조회를 위해 공개 경로다. 운영에서는 list ID의 추측 난이도, TTL, 사용자 귀속 검증을 재점검해야 한다.

## 7. 유지보수 계약과 검증

### BE 팀이 이후 변경에서 지킬 계약

- public API는 `ApiResponse<T>` envelope를 유지하고 `List<T>`인지 page/object인지 명세에 명확히 적는다.
- AI 전용 `/internal/**`는 항상 `X-Internal-Token`으로 보호하며 FE에 노출하지 않는다.
- I-1 후보 응답과 CH-5 화면 카드 응답의 책임을 섞지 않는다. 화면 가격·이미지의 최종 권위는 CH-5다.
- stream ticket의 issuer/audience/scope나 `llmSseUrl` 경로를 바꾸면 FE와 AI 설정·테스트를 같은 변경에서 맞춘다.
- sample SQL은 직접 고치지 않고 generator를 수정한 뒤 재생성한다.

### 실행 검증

```bash
# JDK 21이 PATH에 있거나 JAVA_HOME으로 지정되어 있어야 한다.
bash ./gradlew test
```

현재 통합 수정 기준으로 전체 Spring 테스트가 통과했다.
