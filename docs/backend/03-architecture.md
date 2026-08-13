# 03. 아키텍처 & 컨벤션 명세

> 이 문서는 "어떻게 짤지"를 재결정하지 않게 하는 문서다. 구현 세션은 여기 적힌 구조·규약을 그대로 따르고, 바꾸고 싶으면 코드가 아니라 이 문서를 먼저 고친다.

## 1. 시스템 구성

```
브라우저 ─ Next.js(FE) ─┬─ /api (JWT·게스트쿠키) ─────▶ Spring Boot(BE) ─┬─ MariaDB (커머스)
                        │                          ▲     │ 티켓발급    └─ Redis (세션 TTL·RS256 키)
                        │        /internal 콜백(토큰) │     │ (RS256)
                        └─ 채팅 SSE (단명 티켓/JWKS) ──────▶ FastAPI(LLM팀) ─── Vector PG (임베딩·LLM 소유)
```

- **신원·쓰기의 소유자는 BE 하나.** `/api`(조회·주문·담기·신원)는 전부 Spring. LLM의 쓰기(담기·문의)는 전부 BE `/internal/*` 콜백(`X-Internal-Token`)으로만.
- **채팅 SSE(읽기·응답 표시)만 FastAPI 직결** — FE가 Spring이 발급한 **단명 서명 티켓(RS256)** 으로 FastAPI에 직접 연결하고, FastAPI는 **JWKS로 티켓만 검증**한다(신원을 만들지 않고 되돌려줌). 상세 D5. *과거엔 Spring SSE 패스스루였음(D5 취소선).*
- **커머스 DB(MariaDB)는 Spring만**(D7). FastAPI는 자기 소유 **Vector PG(임베딩)** 만 직접 붙고 커머스 DB엔 안 붙는다(D-분산9, D7 예외).
- 대화 내용은 어디에도 저장하지 않는다(기능 정의 확정). BE는 세션 ID 발급/TTL·티켓 서명만 관리, 멀티턴 맥락은 FastAPI 인메모리.

### 1-1. 배포 형상 — 단일 서버 단계 (2026-07-08 스터디 결정. 분산 형상은 §1-2)

> **[2026-07-16 현황]** 실제 배포는 이미 **§1-2(분산) 방향**으로 가 있다 — 커머스 DB는 compose 컨테이너가 아니라 **RDS(MariaDB)** 로 외부화됨. §1-1은 "왜 이렇게 나눴나"의 근거·데모 최소 형상으로 유지하되, 상태(DB)는 §1-2/D-분산9 기준(외부화)이 현행이다.

EC2 1대 + docker-compose, 컨테이너 6개(nginx/next/spring/fastapi/mariadb/redis).

```
인터넷 ──:80/443──▶ [nginx] ── /          ─▶ next:3000
                            ── /api/**    ─▶ spring:8080
                            ── /internal/** ─▶ 404 (라우팅하지 않음)
docker 내부망:  spring ─▶ mariadb:3306, redis:6379 / spring ◀▶ fastapi:8000 / next(서버측) ─▶ spring:8080
```

- **외부에 publish되는 포트는 nginx의 80/443뿐.** spring/next/fastapi/mariadb/redis는 내부망 `expose`만 — 배포 compose에 `ports:` publish를 적는 순간(특히 spring 8080) nginx를 우회하는 뒷문이 생긴다.
- **`/internal` 3중 방어**: ① nginx가 라우팅하지 않음(경로 차단) ② spring 포트 미노출(네트워크 차단) ③ 서비스 토큰 필터(애플리케이션 검증). ①②는 네트워크 수준이라 토큰이 유출돼도 외부에선 쓸 곳이 없다.
- MariaDB 데이터는 named volume으로 컨테이너 생명주기와 분리. RDS 전환은 분산 단계에서 검토.
- **FE base URL이 2개** (FE 팀 공유 필요): 브라우저 발 호출은 `NEXT_PUBLIC_API_URL`(도메인, nginx 경유), Next 서버 컴포넌트 발 호출은 `API_URL`(`http://spring:8080`, 내부망 직행).

### 1-2. 배포 형상 — 분산 단계 (2026-07-08 스터디 결정)

> §1-1(단일 서버)은 모든 계층이 단일 = 전부 SPOF다. 부하 관리·무중단 배포·failover를 위해 **무상태 계층만 복제**하고 **상태는 외부화**한다. 가르는 기준 한 단어: **상태(stateful vs stateless)**.

```
                      ┌───────┐
                      │  ALB  │              ← 층 1: 인스턴스 바깥·AWS 관리형 · 공개 진입점
                      └───┬───┘
          ┌───────────────┼──────────────────────────┐   ※ 직결(D5) 후 FastAPI도 공개 대상:
          ▼               ▼                           ▼     SSE는 FE ↔ FastAPI 직접(티켓 검증)
    ┌──────────┐    ┌──────────┐                ┌─────────┐
    │ nginx    │    │ nginx    │                │ fastapi │  ← 앱 티어 B: LLM · **1대 고정 · 공개**
    │  ├ next  │    │  ├ next  │                └────┬────┘     D-분산8 · 커머스 DB엔 직접 안 붙음(D7)
    │  └ spring│    │  └ spring│◀──/internal 콜백────┘        fastapi → spring = /internal 콜백
    └────┬─────┘    └────┬─────┘                     │        spring → fastapi = P-5 추천·세션정리
         │               │            (FastAPI 전용) ▼
         │               │                  ┌──────────────────┐
         │               │                  │ Vector PG(pgvec) │ ← 임베딩 상태 · D7 예외(공개 카탈로그)
         └───────┬───────┘                  └──────────────────┘
                 ▼
        ┌───────────────────┐
        │  RDS · ElastiCache │       ← 커머스 공유 상태 (오직 spring 접근) · Multi-AZ / primary+replica
        └───────────────────┘
```

> 위 그림은 **분산/프로덕션 목표 형상**. **앱 티어 A(nginx+next+spring)만 무상태 복제**, fastapi는 별도 티어·1대 고정(D-분산8), 상태(커머스 RDS·ElastiCache·Vector PG)는 전부 외부화. 데모/단일 서버 단계(§1-1)에선 fastapi·Vector PG를 별도로 빼지 않고 compose 한 박스에 co-location 가능 — 과분리 금지(D-분산8).

**D-분산1. 복제/공유 경계 = 상태 외부화.** next·spring·fastapi는 무상태 → 복제. mariadb·redis는 상태를 들고 있어 복제하면 세계가 갈라짐 → 공유. spring이 무상태 칸에 들어갈 수 있는 건 이미 (a) 인증을 JWT로 해 로그인 상태를 메모리에 안 두고, (b) 채팅 세션을 Redis TTL로 외부화했기 때문. **분산 전환 시 spring 코드 변경 없음.** (단, fastapi는 별도 티어로 빼되 복제 없이 1대만 둔다 — D-분산8.)

**D-분산2. DB 이중화 = RDS Multi-AZ, read replica는 두지 않는다.** 이중화 이유는 읽기 부하가 아니라 failover(죽으면 전체가 죽음)다. JARVIS 트래픽의 병목은 DB가 아니라 LLM 대기(FastAPI)라 read replica의 근거가 미달. Multi-AZ는 물리 2대(서로 다른 AZ)·동기 복제·자동 승격을 엔드포인트 DNS 하나 뒤로 추상화 → 앱은 단일 URL만 보고 읽기/쓰기 분리도 없음. **부수 효과로 복제 지연·read-your-own-writes 문제를 애초에 회피**(대기 서버는 읽지 않으므로). *비용(상시 2배)상 데모 기간엔 단일 인스턴스로 두고 "프로덕션이면 Multi-AZ 토글 ON"으로 운영 가능 — 코드 영향 없음.*

**D-분산3. Redis = ElastiCache primary+replica.** 자동 failover, 앱은 단일 엔드포인트. mariadb과 동형.

**D-분산4. 로드밸런서는 2층.** 층 1(인스턴스 간 분배)은 **반드시 인스턴스 바깥**에 있어야 한다 — 특정 EC2 안에 두면 그 EC2가 죽을 때 분배기도 같이 죽어 SPOF가 그대로 이동. AWS ALB(관리형)를 쓰면 LB 자신의 이중화를 AWS가 떠안음(nginx를 별도 EC2에 직접 두면 그 EC2가 다시 SPOF). 층 2(인스턴스 안 nginx)는 §1-1 역할 유지: next/spring/fastapi 라우팅 + spring 8080 외부 미노출. **`/internal` 3중 방어는 그대로 유지**되고 앞단에 ALB가 한 겹 더 붙는 셈(보안그룹으로 각 EC2 nginx 포트는 ALB에서 온 것만 허용).

**D-분산5. 스케줄러는 분산 안전하게 — 조건부 UPDATE + Redis 분산 락.** 상세는 [01 §6](01-order-state-machine.md#6-스케줄러-명세). 다인스턴스에서 같은 잡이 중복 실행되므로 (a) 전이 쿼리는 `WHERE status=<이전>` 조건부 UPDATE(정합성 최종 방어선), (b) 잡 레벨은 Redis 분산 락(ShedLock)으로 매 틱 1대만 실행(중복 부수효과 차단). 01 §6의 "인스턴스 1대 전제" 폐기.

**D-분산6. 채팅 SSE의 분산 대응 (직결 후 = FE ↔ FastAPI).** 채팅은 SSE(장수명 HTTP 연결)라 짧은 요청을 가정한 중간 장비(ALB)와 충돌한다. 직결(D5)로 SSE는 **ALB → FastAPI**를 지나므로 아래는 그 경로에 적용(과거엔 Spring 경유 전제였음). 세 가지:
- **self-pinning / sticky**: FastAPI는 **1대 고정(D-분산8)** 이라 스트림·다음 턴이 어차피 같은 인스턴스로 간다 — sticky 고민 자체가 없음(대화 맥락도 FastAPI 인메모리라 그래야 맞음). *복제하게 되면* 스트림 전체가 TCP 연결 하나로 한 인스턴스에 자동 고정되지만, 맥락 인메모리 때문에 sessionId sticky가 필요해짐(그래서 안 늘림 — D-분산8).
- **idle timeout**: LLM이 뜸 들이는 침묵 구간(>ALB idle timeout, 기본 60s)에 연결이 끊긴다 → FastAPI가 주기적 하트비트(`: ping` 주석) 전송 + ALB idle·read timeout을 넉넉히(예: 300s).
- **버퍼링**: 중간 장비가 응답을 모았다 한 번에 넘기면 SSE 실시간성이 죽는다 → FastAPI가 `text/event-stream` + `X-Accel-Buffering: no`. FastAPI 앞에 nginx를 둔다면 스트리밍 경로만 `proxy_buffering off`(+`proxy_http_version 1.1`).

**D-분산7. 인그레스는 ALB(공개 진입점을 인정), Cloudflare Tunnel 아님.** 기준은 *노출 제거*가 아니라 *노출 인정 + 방어*다. 진입점을 없애는(cloudflared 아웃바운드 터널) 대신, ALB의 로드밸런싱·헬스체크·failover 이득이 "공개 진입점 1개를 감수하는 비용"보다 크다고 판단 → 진입점을 없애지 않고 **nginx 443 하나로 좁히고 보안그룹으로 잠근다**. 이건 `/internal` 3중 방어(포트를 없애자가 아니라 필요한 문만 열고 나머지를 네트워크·토큰으로 좁힘)·D-분산4(관리 이득을 위해 관리형 진입점을 받아들임)와 **동일한 철학 — 문을 없애는 게 아니라 좁혀서 지킨다.** 터널은 "문을 없앤다"는 다른 철학이라, 로드밸런싱 이득을 포기하면서까지 갈아탈 근거가 미달.

**D-분산8. fastapi는 별도 티어 + 1대 고정 — 복제하지 않는다. (직결 후 = 공개·1대)** D-분산1이 "무상태는 복제"라 했으나 fastapi는 **별도 티어로 빼되 복제는 안 하고 1대만** 둔다.
- **왜 별도 티어(next·spring과 co-location 안 함)**: ① 자원 모양이 다름 — 임베딩·벡터·GPU 가능성, 동시성·메모리 프로파일이 spring과 상이 → 인스턴스 타입을 독립적으로 고를 수 있게. ② LLM팀 소유·다른 런타임 → 독립 배포. ③ 장애·자원 격리 — fastapi hang/누수가 같은 박스의 CRUD를 굶기면 안 됨.
- **왜 그런데 1대만(복제 안 함)**: ① **죽어도 됨** — fastapi 실패는 `LLM_UNAVAILABLE`로 degrade하고 쇼핑몰(next/spring/DB)은 정상(D5). next/spring/DB의 실패는 서비스 전체를 죽이지만 fastapi는 아니라 **SPOF여도 괜찮은 유일한 계층.** ② **부하가 병목 아님** — 채팅은 외부 LLM 호출 대기(I/O)가 대부분이라 async 한 대가 동시 SSE 여럿 감당. 트래픽 방어는 복제가 아니라 **rate limit(05 §3) + degrade + 수직 확장**이 먼저(상류 LLM API 한도가 진짜 천장이라 복제로 안 풀림). ③ **대화 맥락이 인메모리** — fastapi는 멀티턴 맥락을 자체 메모리에 sessionId로 유지(05). **1대면 모든 턴이 같은 인스턴스로 가 맥락이 그대로 유지**된다. 복제하면 턴마다 다른 인스턴스로 가 맥락이 갈라져 외부화/sticky가 필요해지는데, **안 늘림으로써 그 복잡도를 통째로 회피**(Redis는 세션 ID·TTL만 들고, 대화 맥락은 fastapi 메모리라 이 회피가 성립).
- **[2026-07-16] 직결로 바뀐 것 — "비노출 1대" → "공개 1대"**: SSE가 FE↔FastAPI 직결(D5)이라 fastapi는 **인터넷 공개 진입점**이 된다. 단 **복제는 여전히 안 함** — "공개"와 "복제"는 별개 축이고, 1대의 근거(맥락 인메모리)는 그대로다. 공개에 따른 책임(TLS·CORS·rate limit·티켓 검증)은 FastAPI로 이동(D5·§8). 보안그룹으로 좁힘: 공개 포트는 SSE(읽기)만, `/internal` 콜백은 여전히 spring 티어에서 온 것만.
- **정말 복제가 필요해지면(미래 레버) — 조회/생성 분리**: fastapi 통째 복제(맥락 갈라짐) 대신, **stateless 조회(벡터검색·`/internal` 콜백)는 복제**하고 **stateful 생성(SSE 홀딩 + sessionId 인메모리 맥락)은 고정/단일**로 쪼갠다(D-분산1을 fastapi 내부에 적용). 데모 단계엔 과분리라 안 함 — 진짜 병목(LLM API 대기)은 이 분리로도 안 줄어듦.
- **유지되는 것**: **분산 전환 시 fastapi만 단일, 나머지(next/spring)는 복제.** 데모/단일 서버(§1-1)는 compose 한 박스 co-location. spring→fastapi 호출(P-5·세션정리)은 `LLM_BASE_URL`로, fastapi→spring 콜백은 `/internal` + `X-Internal-Token` — 3중 방어 불변.

**D-분산9. 벡터 DB(Vector PG/pgvector)도 상태 외부화 — FastAPI 소유, D7 예외.** LLM 추천의 의미검색용 임베딩 스토어(`productId·attributes·embedding`, 05 §1)는 **상태(stateful)** 라 D-분산1대로 외부화한다 — MariaDB와 동형(데모=compose 컨테이너+volume, 분산=RDS PostgreSQL/pgvector).
- **왜 인스턴스 안에 안 두고 밖으로**: "1대라서"가 아니라 **생명주기 분리** — 임베딩 재생성은 비쌈(크롤링 상품 1만+ 임베딩 = API 비용·시간)이라 FastAPI 박스와 운명을 묶지 않는다(재배포·교체해도 데이터 생존). + 인덱스 빌드/배치 upsert의 CPU·메모리 스파이크가 같은 박스의 async SSE 서빙을 굶기지 않게(자원 격리).
- **failover는 후순위**: 벡터DB는 MariaDB에서 **재생성 가능**(파생 데이터)이고 down 시 정형-only 추천으로 degrade되므로, Multi-AZ는 커머스 RDS보다 우선순위 낮음(비용 보고 토글).
- **D7 예외인 이유**: D7(모든 DB는 Spring만)은 *커머스 DB(주문·PII·카탈로그)* 대상. 벡터DB는 **공개 카탈로그의 임베딩(PII·쓰기 없음)** 이라 "유출 개념 없는 공개 데이터" → FastAPI 직접 접근 허용해도 D7 threat model 밖. 커머스 RDS는 여전히 **오직 Spring**.
- **정합성**: 가격·재고 같은 치명적 정형 진실은 **항상 Spring/MariaDB에서 확인**(추천 후보조회 1왕복·카드 하이드레이션 2왕복이 진실 원천). 벡터DB의 attributes는 **배치 동기화**라 다소 낡아도 무방 — 랭킹 순서만 흔들 뿐 거짓 가격·품절을 못 만든다(05 §1).

## 2. 결정 로그

### D1. 패키지 구조는 도메인 우선(package-by-feature)

- **선택지**: (A) 레이어 우선(controller/, service/, repository/에 전 도메인 혼재) (B) 도메인 우선(order/ 안에 controller+service+repository)
- **기준**: 이 프로젝트는 도메인이 12개 이상 — 레이어 우선이면 controller 패키지에 파일 15개가 쌓여 탐색 비용이 커진다. 구현 세션(Opus)이 "주문 기능"을 만질 때 order/ 폴더만 열면 되게.
- **선택**: (B).
- **트레이드오프**: 공통 코드의 위치가 모호해질 수 있음 → `global/`에 격리(아래 §3).

### D2. 응답은 공통 envelope로 감싼다

- **기준**: FE가 성공/실패를 HTTP 상태 + 일관된 body 구조로 판별할 수 있어야 하고, LLM 콜백도 같은 규약을 쓰면 계약 문서가 얇아진다.
- **형식**:
```json
// 성공
{ "success": true, "data": { ... } }
// 실패
{ "success": false, "error": { "code": "ORDER_INVALID_TRANSITION", "message": "배송중인 상품은 취소할 수 없습니다." } }
```
- 에러 `code`는 `<도메인>_<사유>` 대문자 스네이크. message는 사용자 노출 가능한 한국어 문장.
- **필드 검증 실패(400)는 `VALIDATION_ERROR` + `fields` 배열** — `error.fields: [{field, message}]`로 어느 필드가 왜 틀렸는지 명시 (2026-07-17 FE 요청, A-1 등 폼 API 공통).
- **401 코드 2종 분리** (2026-07-17 FE 요청): `AUTH_REQUIRED`(토큰 없음 — FE는 로그인 유도) / `AUTH_TOKEN_EXPIRED`(만료 — FE는 refresh 후 1회 재시도). 구분 없이 쓰던 기존 표기는 폐기.
- **에러 부가 데이터 `error.detail`** (2026-07-18 Phase 5 추가): 에러 응답에 구조화 데이터가 필요한 경우 `error.detail` 객체로 동반(없으면 필드 생략). 현재 사용처: `CART_OPTION_REQUIRED`의 `detail.options[{optionId, name, extraPrice}]` — LLM 되물음용(05 §I-2), FE도 동일 수신. `CART_STOCK_INSUFFICIENT`의 `detail.availableStock`(남은 재고 — 04 §3). `ORDER_PRODUCT_UNAVAILABLE`의 `detail.unavailableItems[{productId, name, reason}]` (2026-08-05 — 불량 품목 전량. `reason`은 목록의 `purchaseState`와 같은 어휘 `SOLD_OUT`/`HIDDEN`이라 주문 화면과 장바구니가 다른 말을 하지 않는다).
- **날짜 직렬화**: 모든 날짜·시각 필드는 ISO 8601 + 타임존 오프셋(`2026-07-10T14:23:00+09:00`) — Jackson 직렬화 설정으로 전역 적용 (2026-07-17 FE 요청, 아래 타임존 항 참조).
- HTTP 상태: 400(검증/전이 위반) 401(미인증) 403(권한) 404(없음) 409(중복: 이메일, 재신청 등) 500.

### D3. 인증은 JWT AT(30분) + RT(14일, DB 저장)

- 일반(이메일) 로그인만. **OAuth는 MVP 제외**(2026-07-07 팀 결정, 고도화 후보) — 도입 시 Spring Security OAuth2 Client를 같은 JWT 발급 구조 위에 얹는다(토큰 체계 변경 없음).
- **AT도 HttpOnly 쿠키다 (2026-08-04 변경 — 구: `Authorization: Bearer`)**: `access_token`, `Path=/`, `SameSite=Lax`, **쿠키 `Max-Age`는 RT와 같은 14일**(토큰 수명 30분과 다르다 — 바로 아래 항목). 헤더 경로는 **폐기** — 병행하지 않는다. 근거 세 가지 — ① JS가 읽을 수 없어 **XSS로 토큰이 새지 않는다**(헤더 방식은 어디든 저장해야 하고, 저장한 곳은 스크립트가 읽는다) ② FE의 **SSR 첫 진입**에서 브라우저 메모리에 토큰이 없어 로그인 상태를 못 살리던 제약이 풀린다 ③ 로그아웃 시 서버가 쿠키를 만료시켜 **확실히 끊을 수 있다**. RT는 HttpOnly 쿠키(`Path=/api/auth` — 전송 범위 최소화). 재발급: `POST /api/auth/refresh`(AT·RT 쿠키를 함께 회전). 로그아웃도 RT 쿠키 기준 — AT 만료 상태에서 로그아웃이 막히면 안 됨(04 A-3).
- **쿠키 수명(`Max-Age` 14일)과 토큰 수명(`exp` 30분)은 다른 것이다 (2026-08-04 수정)**: 토큰의 진짜 만료는 JWT의 `exp`가 정하고 서버가 그걸로 판정한다. 쿠키의 `Max-Age`는 **브라우저가 이 값을 언제까지 들고 있을지**일 뿐이다. **둘을 같게(30분) 두면 조용한 재발급이 죽는다** — 30분 정각에 브라우저가 쿠키를 스스로 지워 서버가 만료된 토큰을 *보지 못하고*, 그래서 "재발급하라"(`AUTH_TOKEN_EXPIRED`)가 아니라 "로그인하라"(`AUTH_REQUIRED`)로 답한다. RT가 14일 남았는데도 FE는 로그인 화면으로 보내 **30분마다 강제 로그아웃**이 된다. 쿠키를 남겨둬야 서버가 만료를 인지하고 A-4로 유도할 수 있다. 만료된 값이 브라우저에 더 오래 남지만 **그 값은 아무 권한도 주지 않는다**(인증 필요 경로는 401, 로그인·가입은 쿠키를 보지 않고 자격증명으로 판정).
- **탈취된 AT를 즉시 끊는 장치 — 토큰 에폭 (2026-08-04 추가, 07 §3-2)**: AT는 서명만 맞으면 통과하므로 탈취본은 수명(30분)이 다할 때까지 막을 수 없었다. `auth:epoch:{memberId}`에 **무효화 시각**을 적어두고, 토큰의 `iat`가 그보다 이르면 거절한다. **로그아웃이 현재 유일한 트리거**(비밀번호 변경·탈퇴·정지 API는 post-MVP 보류 — 생기면 `TokenEpoch.invalidate()`를 그 자리에서 부른다). Redis 장애 시 **fail-open** — 신원은 서명으로 이미 확정돼 있어 "무효화만 잠깐 미적용"에 그치고, 막으면 Redis 장애가 전원 로그아웃이 된다. **이게 세션 대신 JWT를 남겨둔 값어치다.** 무효 토큰의 401은 `AUTH_TOKEN_EXPIRED`라 FE가 A-4로 자동 복구하고, RT 없는 탈취자는 거기서 죽는다.
- **AT와 RT의 속성이 다른 건 의도다**: RT는 `Strict`+`/api/auth`로 좁힌다(14일 장수명이라 노출 면적을 최소화). AT는 `Lax`+`/`여야 한다 — 모든 API가 인증을 읽고, 외부 링크로 들어오는 **첫 GET에 쿠키가 실려야** 로그인 상태가 유지된다(`Strict`면 그 진입에서 로그아웃으로 보인다).
- **CSRF 토큰은 두지 않는다**: `Lax`는 크로스사이트 POST·PUT·DELETE에 쿠키를 싣지 않으므로 상태를 바꾸는 요청은 막힌다. **전제는 "GET으로 상태를 바꾸는 API를 만들지 않는 것"** — 이 규칙이 깨지면 재검토한다(`SecurityConfig`의 `csrf.disable()` 주석에 같은 근거를 남겨둠).
- **응답 body에서 `accessToken`이 사라졌다** — A-1·A-2는 `member`만, A-4는 `data: null`. FE는 토큰을 만지지 않는다(jarvis-frontend PR #72와 동시 배포).
- **쿠키 Secure 속성(2026-07-18 확정)**: `refresh_token`·`guest_id` 쿠키 모두 `Secure` 부여 — 14일 장수명 RT가 평문 HTTP로 전송돼 온-패스 공격자에게 탈취되는 경로를 차단(운영은 nginx HTTPS, D1-2). 값은 프로퍼티 `app.cookie.secure`(기본 `true`, env `APP_COOKIE_SECURE`)로 제어. `localhost`/`127.0.0.1`은 브라우저가 secure context로 취급해 http에서도 Secure 쿠키를 전송하므로 로컬 개발은 기본 `true` 그대로 동작하고, 비-localhost origin으로 테스트할 때만 `false`로 내린다. `SameSite`(RT=Strict, guest=Lax)는 CSRF 표면만 막고 네트워크 수동 탈취는 못 막으므로 Secure가 별도로 필요.
- **RT 형식은 서명 토큰(JWT)이 아니라 불투명 랜덤 256bit** (2026-07-17 Phase 1 구현 결정): RT의 진실은 어차피 DB 행(02 D6·D17)이라 자체 서명 검증이 무의미하고, claim이 없어 유출 시 노출 정보도 없다. 검증은 SHA-256 해시 대조 + expires_at 확인으로만. 재발급 시 회전(기존 행 삭제 + 새 토큰 발급).
- Spring Security 필터 체인: JWT 검증 필터 → 권한(Role) 검사. `/api/auth/**`, 상품 조회 계열(`/api/products/**`), `POST /api/chat/sessions`(CH-1 티켓 발급)·`POST /api/chat/tickets`(CH-1b)·`GET /api/chat/lists/**`(CH-5 추천 목록), `/api/cart/**`(게스트 쿠키 허용 — 02 D30), `POST /api/events`(E-1 수집 — 인증 선택: JWT 있으면 검증)는 permitAll. *채팅 SSE 자체는 Spring이 아니라 FastAPI가 티켓으로 검증(D5)이라 Spring permitAll 대상이 아님.*
- 게스트: `guest_id` HttpOnly 쿠키(UUID, **Max-Age 2시간 sliding** — 2026-07-31 개정, 구 30일 영속 폐기). 게스트는 "로그인과 로그인 사이의 익명 구간"이라 활동마다 연장(`GuestCookieSlidingFilter`)되고 2시간 무활동이면 만료되며, 로그인·가입 시 반납(삭제)된다(GUEST-LIFECYCLE). 없으면 게스트 식별이 필요한 첫 요청(채팅·장바구니 담기 — 02 D30) 시 발급하며, **발급 = 쿠키 세팅 + guest 행 INSERT가 한 동작**(cart_item·behavior_events의 guest_id FK가 전제하는 선행 조건). 쿠키 발급 전 게스트 행동은 `session_key`로 **익명 추적은 되지만**(02 D31) guest_id가 없어 가입 승계 대상은 아님 — 감수(2026-07-17 갱신).

### D4. internal API는 고정 서비스 토큰 헤더로 인증한다

- **선택지**: (A) 고정 토큰 헤더 (B) 서비스 간 JWT (C) mTLS/네트워크 격리
- **기준**: 데모 규모에서 "FE 경유로는 절대 호출 불가"만 보장하면 됨.
- **선택**: (A). `X-Internal-Token: <env>` 헤더를 검증하는 필터를 `/internal/**`에만 적용. 토큰은 양쪽 `.env`로 공유, 코드/레포에 하드코딩 금지.
- **트레이드오프**: 토큰 유출 시 전체 노출 — 데모 환경 감수. 배포 시 `/internal/**`은 외부 라우팅에서 제외하면 이중 방어.

### D5. 채팅 SSE = FastAPI 직결 + 단명 티켓 핸드오프 (2026-07-16 확정 · RS256/JWKS)

> **연혁**: 원안은 "Spring SSE 패스스루"(아래 취소선 본문, 2026-07-08~). **2026-07-15** 직결 전환 결정 → **2026-07-16** 티켓 방식(RS256+JWKS)·추천 데이터 흐름 확정. 근거: 노션 자료실 **「추천 에이전트 흐름」**·**「JWKS 방식 검토 후 제안」** + LLM 팀 합의. 패스스루가 대규모 트래픽에서 **스트림당 소켓 2개(FE↔Spring, Spring↔FastAPI)를 릴레이**하는 비용이 병목이라 폐기.

- ~~FE `POST /api/chat` → BE가 세션 검증 후 FastAPI로 스트리밍 요청 → 응답 SSE 이벤트를 그대로 FE에 중계~~ **(패스스루 폐기)**
- ~~구현: MVC + `SseEmitter` + WebClient. **왜 Spring이 중개하나**: 소켓을 FastAPI에 넘길 방법이 없어 단일 진입점을 지킴~~ **(직결로 대체 — 아래)**

**직결 구조 (읽기/응답 표시 경로만)**

- **읽기 경로 = FE ↔ FastAPI 직접 SSE 연결.** Spring은 스트림 소켓을 릴레이하지 않는다. `SseEmitter`/WebClient 브리지 제거.
- **인증 = 단명 서명 티켓(auth handoff).** 신원 검증의 소유자는 여전히 Spring — FastAPI는 검증만 한다.
  - **발급**: 채팅 진입 첫 요청에서 Spring이 신원 확인(회원=JWT AT / 게스트=`guest_id` 쿠키) 후 **스트림용 단명 JWT를 RS256으로 서명**해 발급. 세션 발급(CH-1)에 얹어 **추가 왕복 없음**. private key는 Spring만 보관·회전.
  - **검증**: FastAPI가 **JWKS**(`GET /.well-known/jwks.json`)로 public key를 조회(캐싱 + `kid` miss 시 refetch)해 `signature/exp/iss/aud/scope` 검증. **stateless** — Redis/DB 안 봄(D7 "Redis는 Spring 전용" 유지).
  - **티켓 claim**: `sub`(userId|guestId), `sub_type`(member|guest), `iss:jarvis-spring-auth`, `aud:jarvis-fastapi-ai`, `scope:chat:stream`, `exp`(발급 +30~60초).
  - **왜 전권 AT를 직접 안 쓰나**: `EventSource`(GET)는 커스텀 헤더를 못 실어 AT가 **쿼리스트링에 노출**(액세스 로그·히스토리 잔존); 전권 AT의 `aud`에 FastAPI를 더하면 토큰 혼용 방지 취지와 어긋남. → **30~60초 read-only 티켓만** 내보내 유출 피해를 "채팅 스트림 1회 연결"로 봉쇄.
  - **게스트 커버**: 게스트도 Spring이 동일 경로로 발급(`sub_type:guest`, 개인화 미적용). "게스트는 JWT가 없다" 문제를 발급 단계로 흡수 — 회원/게스트가 같은 경로.
  - **one-time 근사**: stateless라 진짜 1회용(재사용 원천 차단)은 상태 저장이 필요 → 짧은 TTL로 근사. 데모 규모에서 충분.
- **쓰기는 불변**: LLM 쓰기(담기·문의)는 여전히 Spring `/internal/*` 콜백(D7) + `X-Internal-Token`(D4). 직결은 **읽기 경로에만**.
- **FastAPI로 이동한 책임**: ① 공개 문 경비 — TLS·CORS·rate limit·공개 DNS/LB. ② SSE 배관 일체 — `text/event-stream`·버퍼링 off(`X-Accel-Buffering:no`)·하트비트 `: ping`·**클라 이탈 시 LLM 생성 취소**(비용)·백프레셔·스트림 내 `error` 이벤트. → §8의 "열림" 항목이 Spring이 아니라 FastAPI 숙제로 이동.
- **타임아웃**: FastAPI↔Spring `/internal` 콜백 3s(05 §3). 스트림 수명·하트비트 정합은 FastAPI 소관(§8). 자동 재시도 없음(LLM 중복 비용 방지) — 재시도는 FE 버튼.
- **후속 반영됨**: 이 전환으로 D-분산6/D-분산8(§1-2)·§7 ③·§8·04(티켓 발급 + `GET /api/products/cards`)·05 갱신. ERD는 티켓 stateless라 변경 없음.

### ~~D6. user_event 적재는 Spring 이벤트 + @Async + AFTER_COMMIT~~ (2026-07-17 이벤트 수집 전환으로 폐기 → 02 D31·D32)

- ~~서비스 레이어에서 `ApplicationEventPublisher.publishEvent()` → `@Async @TransactionalEventListener(phase = AFTER_COMMIT)`가 INSERT.~~ 행동 이벤트는 이제 **FE가 수집 API(`POST /api/events`, E-1)로 배치 전송**하고 서버는 `behavior_events`에 적재만 한다(02 D31) — 서버 내부 발행 경로 자체가 사라짐. BE가 직접 남기는 로그는 성격이 달라 @Async가 **부적합**: `order_status_logs`·`product_change_logs`는 도메인 로직과 **같은 트랜잭션**(로그 누락이 구조적으로 불가능해야 함 — 01 D12), `account_event_logs`는 Spring Security 성공/실패 핸들러에서 INSERT. 기존 근거(롤백 유령 이벤트 방지)는 같은-트랜잭션 방식에선 자동 충족.

### D7. 모든 DB 접근은 Spring만 — LLM에 read-only DB 접근도 주지 않는다 (2026-07-09 팀 합의)

- **선택지**: (A) DB 접근은 Spring 전용, LLM은 `/internal` 창구만 (B) LLM에 read-only DB 접근 허용(위험을 뷰·권한으로 보완).
- **비판적 판단 — 데이터를 둘로 갈라 본다**:
  - **주인 있는 데이터(주문·판매자 지표·PII)**: B는 위험을 넘어 **아키텍처적 모순.** 행 단위 스코핑("이 판매자는 자기 브랜드 행만")을 read-only DB로 하려면 매 쿼리에 "현재 대화자 신원"을 실어야 하는데, 쿼리 주체가 FastAPI라 결국 FastAPI의 신원 주장을 DB가 믿어야 한다 → "신뢰 근원은 JWT, FastAPI는 메아리"(05 §0-1) 원칙을 되돌림. read-only여도 **읽는 것 자체가 피해**라 "쓰기 금지"가 방어가 안 됨.
  - **공개 데이터(상품 카탈로그·후기)**: 유출 개념이 없어 저위험. 여기선 A가 과설계라는 비판이 성립. 단 `products` 전체가 공개는 아님 — 원가·마진·HIDDEN은 사유 → 노출은 반드시 **정제된 뷰/DTO**로(`SELECT *` 아님).
- **선택**: (A). B가 공개 데이터에서 유일하게 이기는 축은 "LLM팀 개발 속도" 하나인데, 그건 **A를 개선(엔드포인트를 굵고 유연하게 + 캐시 + 병렬 툴호출)** 으로 대부분 회수된다. 반면 B는 이중 접근 경로의 상시 감사·text2SQL 오류·공유 DB 부하라는 고정비를 남긴다.
- **인젝션 방어는 별개로 필수** — 경계(문 6개)는 인젝션 성공 시 *피해 반경*을 가둘 뿐, 성공 *확률*을 낮추지 않는다. 역할 분담: LLM팀 = 가드레일·프롬프트 하드닝(확률↓), BE = "호출자가 이미 털렸다 가정"하고 모든 `/internal` 입력 재검증 + 신원은 JWT 메아리만 신뢰 + 사이즈 상한(봉쇄). 둘 중 하나가 다른 하나를 면제하지 않는다.
- **트레이드오프(인정)**: "A 유지"는 "지금 그대로"가 아니라 **"A + 엔드포인트 유연화"가 세트.** 병목을 방치하면 LLM팀의 왔다갔다 불만이 정당해지고 그림자 우회를 낳는다.

## 3. 패키지 구조

```
com.jarvis
├── global
│   ├── config/          # Security, JPA Auditing, Async, Scheduling
│   ├── auth/            # JWT provider·필터, 쿠키 매니저(AT/RT/게스트), 토큰 에폭
│   ├── response/        # ApiResponse envelope, ErrorCode enum, GlobalExceptionHandler
│   ├── event/           # 이벤트 수집 API(E-1)·behavior_events 적재
│   ├── cache/           # RedisCache — 읽기 캐시 공용 진입점(07 §3-1, cache-aside)
│   ├── ratelimit/       # FixedWindowCounter(공용 집계) + LoginRateLimiter(A-1·A-2)·UploadRateLimiter(S-6)
│   └── entity/          # BaseTimeEntity (createdAt/updatedAt 공통 상속)
├── member    ├── brand     ├── category  ├── product
├── cart      ├── order     ├── review    ├── wishlist
├── address
├── chat           # 채팅 세션 + 스트림 티켓 발급(RS256/JWKS) — SSE는 FastAPI 직결(D5)
├── recommendation # 추천 목록 사본(02 D38)·귀속 해석·홈 추천 클라이언트 — 컨트롤러 없는 지원 패키지
├── internal       # /internal/* 컨트롤러 (LLM 콜백 전용)
└── seller         # 판매자 지표 조회
```

각 도메인 패키지 내부: `XxxController` / `XxxService` / `XxxRepository` / `dto/` / (필요시) `Xxx` 엔티티. 컨트롤러에 비즈니스 로직 금지, 엔티티를 API로 노출 금지 (CLAUDE.md).

**하위 폴더는 `dto/` 하나뿐이다** — 레이어 폴더(`controller/`, `service/`, `repository/`)를 만들지 않는다. D1이 "order를 열면 주문의 전부가 보인다"를 노린 것인데 안에서 다시 레이어로 쪼개면 그 이득이 사라진다. enum·`@ConfigurationProperties`·스케줄러도 같은 평면에 둔다. `src/test/java`도 같은 트리를 미러링.

**`XxxService`는 도메인당 하나가 아니다** (2026-08-07 명문화 — 위 나열이 단수형이라 오해를 샀다). 서비스를 가르는 기준은 파일 크기가 아니라 **바뀌는 이유**다: `ProductService`(조회 — 응답 모양·정렬로 바뀜)와 `ProductStockService`(재고 — 차감 정책·로그 규칙으로 바뀜)는 같이 두면 한쪽 이유로 다른 쪽이 흔들린다. 실제 분포도 seller 5개, order·member·chat·recommendation·product 각 2개다.

- **`claim`은 독립 패키지가 아니라 `order/` 안에 있다 (2026-08-07 문서 정정)**: `Claim`·`ClaimController`·`ClaimService`·`ClaimRepository`·`ClaimStatus`·`ClaimType`. 취소·반품은 주문 상태 머신(01 §6)의 전이를 그대로 타므로 `ClaimService`가 `OrderStatusChanger`·`OrderRepository`·`OrderItemRepository`를 직접 쓴다 — 분리하면 이 셋이 전부 패키지 경계를 넘는다. **다만 `order/`는 평면 파일 23개 + dto 12개로 전 패키지 중 가장 크다** — 다음에 커지면 분리 1순위.
- **`recommendation`은 컨트롤러가 없다**: 자기 입구 없이 cart·order·product·chat·global.event가 갖다 쓰는 지원 패키지(추천 귀속 해석 + 목록 영구 사본). `dto/`는 FastAPI 홈 추천 호출용.
- **internal 컨트롤러는 자체 로직을 갖지 않고 도메인 서비스를 재사용한다.** 같은 행위(예: 담기)는 같은 서비스 메서드 하나로 — `/api`와 `/internal`은 신뢰 모델이 다른 입구일 뿐, 검증·처리 로직은 서비스 레이어에서 공유. (검증이 컨트롤러에 있으면 입구를 낼 때마다 복붙된다 — 01 체크리스트와 같은 맥락)

#### 3층(Controller→Service→Repository) 밖에도 자리가 있다 (2026-08-07 명문화)

3층은 **요청 하나가 지나가는 길**이지 전체 지도가 아니다. 평면 패키지라 나머지가 섞여 보이므로 넷으로 나눠 읽는다.

| 자리 | 무엇 | 예 |
|---|---|---|
| **입구** | 밖에서 안으로 들어오는 문 | 컨트롤러 / 스케줄러(`OrderMockScheduler`) / 보안 필터(`JwtAuthenticationFilter`·`InternalTokenFilter`) |
| **판단** | 서비스 + 엔티티 | §3-1 |
| **출구** | 안에서 밖으로 나가는 문 | 리포지토리(DB) / 외부 API 클라이언트(`LlmNotifyClient`·`HomeRecommendationClient`) / 캐시(`RedisCache`) |
| **받침** | 어느 길에도 안 속하고 옆에서 거드는 것 | 설정(`SecurityConfig`·`*Properties`) / 공통 응답·에러(`ApiResponse`·`ErrorCode`·`GlobalExceptionHandler`) |

- **외부 API 클라이언트는 리포지토리와 같은 등급이다** — 나가는 문일 뿐이므로 판단을 넣지 않는다. "이 조건이면 안 보낸다"는 서비스가 정한다.
- **보안 필터는 컨트롤러 *앞*이라 `GlobalExceptionHandler`가 닿지 않는다** — 그래서 필터발 401/403은 `EnvelopeAuthenticationEntryPoint`/`EnvelopeAccessDeniedHandler`가 직접 envelope를 쓴다(§3-1 Security 항). 3층 밖에 산다는 사실이 만든 예외다.
- **둘 이상의 도메인이 쓰는 설정·상수는 `global/`로 옮긴다.** 한 도메인만 쓰면 그 도메인에 둔다. *현재 위반*: FastAPI 접속 설정 `chat.LlmProperties`를 `recommendation`이, internal 토큰 헤더 상수 `internal.InternalTokenFilter.HEADER`를 `recommendation`이 참조한다 — 공용인데 처음 쓴 도메인에 자리를 잡아 패키지 순환을 만들었다. 정리 대상.

#### 이름으로 정체를 알 수 있게 (2026-08-07 신설)

평면 패키지의 대가는 "이 파일이 무엇인가"가 폴더로 안 드러난다는 것이다. 접미사가 그 역할을 대신한다.

| 정체 | 이름 규칙 | 예 |
|---|---|---|
| 층 | `~Controller` / `~Service` / `~Repository` | `OrderController` |
| 엔티티 | **업무 명사 단독** (접미사 없음) | `Order`, `CartItem` |
| enum | `~Status` / `~Type` | `OrderStatus`, `ClaimType` |
| 부품 | **`~er`/`~or` 행위자 이름** | `OrderStatusChanger`, `SellerBrandResolver`, `CartEventRecorder` |

- **`~Entity` 접미사는 붙이지 않는다** — 엔티티 이름이 곧 업무 용어라 `OrderEntity`는 말이 어색해진다.
- **`@Service`는 컨트롤러가 부르는 유스케이스, `@Component`는 그 안에서 쓰는 부품**으로 가른다. 스프링에는 기능 차이가 없으므로(실제 동작이 다른 건 `@Repository` 하나뿐) 이건 순수한 팀 약속이다. 흔한 관례("도메인 로직이 있으면 `@Service`")와 다른데, 우리 부품 중 상당수(`OrderStatusChanger`·`SellerBrandResolver`·`CustomerLabeler`)가 도메인 규칙 덩어리라 그 기준으로는 갈리지 않는다. *현재 위반*: `ProductStockService`(부품인데 `@Service`+Service 이름) · `RecommendationListStore`(부품인데 `@Service`) · `MockPaymentService`(부품인데 Service 이름).

### 3-1. 코드 컨벤션 (2026-07-17 신설)

> §3이 "어디에 두나"라면 여기는 "클래스 하나를 어떻게 쓰나". 전 Phase 공통 — 구현 세션은 이 규약과 다르게 짜지 않는다.

**Controller — 번역기 이상 금지**
- 역할은 3개뿐: `@Valid` 요청 DTO 바인딩 → 서비스 호출 → `ApiResponse` 반환. 컨트롤러에 if 분기가 나타나면 서비스로 내릴 신호.
- 인증 주체(userId/guestId)는 `@AuthenticationPrincipal`·ArgumentResolver로만 받는다 — 요청 body의 신원 주장은 불신(§7 "신원은 서버가 채운다"의 코드 버전).
- try-catch 금지 — 예외는 전부 `GlobalExceptionHandler`(§6).

**Service — 트랜잭션과 규칙의 집**
- 클래스에 `@Transactional(readOnly = true)`, 쓰기 메서드에만 `@Transactional` 오버라이드 — 실수로 새는 더티체킹 쓰기 차단 + 읽기 최적화.
- 검증 경계: **형식**(이메일 포맷, 8자+영문숫자)은 요청 DTO의 Bean Validation, **상태·자격**(중복, 전이 가능, 소유권)은 서비스. 이 경계가 무너지면 `/internal` 입구를 낼 때 검증이 복붙된다(§3 internal 항과 같은 근거).
- 타 도메인 접근은 그 도메인의 **서비스를 경유** — 타 도메인 Repository 직접 주입 금지. **금지의 대상은 "쓰기"다** (2026-08-07 정정): 남의 테이블을 바깥에서 고치면 그 도메인이 세운 규칙(전이 검증·로그 동반)을 우회한다.
  - **읽기 전용 주입은 허용한다.** 이름·가격·존재 확인 같은 조회까지 서비스로 감싸면 위임 메서드만 늘고 얻는 게 없다. 단 **① 쓰기 메서드는 부르지 않는다 ② 조회 결과로 남의 엔티티 상태를 바꾸지 않는다.** 현재 이 갈래에 있는 건 `OrderService`(6) · `CartService`(3) · `ReviewService`(3) · `HomeRecommendationService`(3) · `WishlistService`(1) · `global.event.EventService`(1)로, **전부 조회다**(2026-08-07 전수 확인).
  - **예외: `seller/` 집계 서비스.** 판매자 지표는 order·product·brand·review·member의 행을 **가로질러 숫자 하나로 접는 read model**이라, 도메인 서비스를 경유하면 각 도메인이 "판매자 통계용" 조회 메서드를 떠안는다(도메인 규칙 보호가 아니라 오염). **조건은 소유권 필터** — 모든 조회는 `SellerBrandResolver`가 확인한 brandId로 스코핑한다(§3 ⑥·⑦).
    - **seller는 쓰기도 한다** — `SellerProductService`가 I-11/I-12(상품 수정·삭제) 경로에서 `Product`를 고치고 `product_change_logs`를 직접 남긴다. 즉 위 "읽기 전용" 조건 밖이며, **"변경하면 기록한다"가 product 안이 아니라 seller와 product 두 곳의 관행으로 유지된다.** 재고 쪽은 `ProductStockService`로 모았지만(2026-08-07) 가격·상태는 아직이다 — **상품 변경 일체를 product가 소유하도록 옮기는 게 남은 작업.**
  - **의존 방향은 소비자 → 피소비자 한 방향.** `ReviewRepository`가 seller DTO를 만들던 역방향 참조는 제거했다(2026-08-07 — 프로젝션을 `review.dto.BrandReviewRow`로 옮김). 같은 모양(하위 도메인이 소비자의 타입을 아는 것)이 다시 생기면 같은 방식으로 소유자 쪽에 둔다.
- 실패는 null/boolean 반환이 아니라 **`BusinessException(ErrorCode)` 계열 throw** — D2 envelope 매핑과 자동 정합.
- **바꾸면 반드시 남는 것은 "단일 관문"으로 묶는다** (2026-08-07 명문화). 상태·수량을 바꾸는 일과 그에 딸린 기록을 **한 메서드 안에** 두고, 엔티티의 변경 메서드는 공개 범위를 좁혀(package-private) 관문을 우회할 수 없게 한다. 호출자가 "바꾼 뒤 기록도 남겨야지"를 기억하는 구조는 호출자가 늘면 반드시 하나가 빠뜨린다.
  - `OrderStatusChanger` — 주문/아이템 상태 전이 + `order_status_logs` (01 D12). `Order.markPaid()` 등은 `order` 패키지 밖에서 못 부른다.
  - `ProductStockService` — 재고 조건부 차감·보상 복원 + 품절 로그 (02 D33).
  - **아직 관문이 없는 곳**: 판매자의 상품 가격·상태 변경(I-11/I-12). `SellerProductService`가 `Product`를 직접 고치고 `product_change_logs`를 직접 남긴다 → "변경하면 기록"이 seller·product 두 곳의 관행으로 유지된다. **이관이 남은 작업 1순위.**
- **트랜잭션 안에서 바깥(HTTP·Redis)을 기다리지 않는다** (2026-08-07 명문화). 열린 트랜잭션은 DB 커넥션을 점유하므로, 그 안에서 FastAPI 응답을 3초 기다리면 커넥션 풀이 마른다. 이미 실천 중인 예: `ChatSessionService.endSessionAsync()`는 "로그아웃처럼 `@Transactional` 안에서 호출하는 곳" 전용으로 따로 둔 비동기 경로다.
  - 읽기 트랜잭션 안의 캐시 조회(`CategoryService`·`ProductService`의 `RedisCache`)는 허용 — fail-open이라 장애가 조회 실패로 번지지 않고 왕복이 짧다. **쓰기 트랜잭션 안에서는 금지.**

**Entity — setter 없는 도메인**
- `@Setter`/`@Data` 금지, `@NoArgsConstructor(access = PROTECTED)`, 생성은 정적 팩토리 또는 빌더.
- 상태 변경은 의도가 드러나는 엔티티 메서드로(`member.changePassword(...)`) — 01 상태 머신의 전이 검증을 담는 전제.
- `@Enumerated(EnumType.STRING)` 고정(ORDINAL은 enum 순서 변경 = 데이터 파손). `createdAt/updatedAt`은 `BaseTimeEntity` + JPA Auditing 공통화.
- 연관관계는 기본 **단방향 `@ManyToOne(fetch = LAZY)`**, 양방향은 실사용처가 있을 때만. 도메인 경계를 넘는 참조는 객체 대신 id 보관 허용 — 조인 그래프가 전 도메인으로 번지는 것 방지.

**DTO — record + 요청/응답 분리**
- Java record, API 단위 네이밍(`SignupRequest`/`SignupResponse`) — 범용 `XxxDto` 금지(어느 API의 모양인지 이름으로 식별).
- 요청 DTO를 응답에 재사용 금지. 엔티티→응답 변환은 `XxxResponse.from(entity)` 정적 팩토리로 모은다(서비스에 getter 나열 매핑 금지).
- Bean Validation은 요청 DTO에, 실패는 D2의 `VALIDATION_ERROR + fields[]`로 핸들러가 일괄 변환.

**OOP — 지금 상태를 지키는 방어 규칙 (2026-08-07 신설)**

> 네 특성을 "잘 쓰자"로 두면 안 써도 될 곳에 쓰게 된다. 실제 코드를 재보니 캡슐화만 검사 가치가 있고
> 상속·다형성은 **거의 안 쓰는 지금이 좋은 상태**라, 늘어나는 걸 막는 방향으로 적는다.

- **캡슐화** — 필드는 전부 private, setter 금지, 상태 변경은 이름 있는 메서드로, **그 메서드도 필요한 만큼만 공개**한다. *현황: public 필드 0 · `@Setter`/`@Data` 0.*
- **상속보다 조합** — 도메인 클래스끼리 상속하지 않는다. 상속은 프레임워크가 요구할 때만(`BaseTimeEntity`의 `createdAt/updatedAt`, `OncePerRequestFilter`, `RuntimeException`). 공통 로직이 필요하면 그 일을 하는 객체를 만들어 주입한다. *현황: 우리가 만든 도메인 상속 계층 0.*
- **인터페이스는 구현이 둘 이상일 때만** — 구현이 하나면 만들지 않는다. 예외는 "곧 갈아끼울 자리"뿐이고 근거를 주석에 남긴다. *현황: `PaymentService` 1개(구현 `MockPaymentService` — 실 결제 교체 자리).*

**동시성 — 무엇을 언제 쓰나 (2026-08-07 명문화)**

> 장치별 결정은 01·02에 흩어져 있었고 선택 기준만 없었다. 아래는 코드에서 뽑은 것이다.

| 상황 | 장치 | 사용처 |
|---|---|---|
| 읽고 → 계산하고 → 쓰는 사이에 끼어들 수 있다 | 비관적 락 `findByIdForUpdate` | 재결제(01 §2-1) · 장바구니 합산 · 판매자 상품 수정 |
| 조건이 곧 갱신 대상이다 (재고 ≥ 수량) | **조건부 UPDATE 한 방** — 락보다 싸다 | 재고 차감(02 D33) · 아이템 상태 전이 |
| 여러 행을 연달아 잠근다 | **항상 같은 순서로**(productId 오름차순) | `ProductStockService.deduct()` — 정렬 보장은 호출자가 아니라 관문이 진다 |
| 서버 여러 대 중 하나만 해야 한다 | Redis `SETNX` | 채팅 세션 소유권 · 캐시 스탬피드 방지 |
| 같은 요청이 두 번 와도 한 번만 | 멱등 장치 | 아래 별도 항 |

**멱등성 — 두 번 와도 한 번처럼 (2026-08-13 신설)**

> 05 계약이 **"자동 재시도 없음"**(중복 담기·중복 과금 방지)이라 재시도 폭주는 없다. 그래도
> **사용자 수동 재시도 · 멀티탭/다중 기기 · 콜백 동시 도착**은 남으므로, 쓰기 입구는 이걸 전제로 짠다.

- **쓰기 입구를 낼 때 "두 번 오면?"에 답이 있어야 한다.** 답이 "괜찮다"면 멱등 장치를, "괜찮지 않다"면 **그 이유를** 남긴다. 둘 다 없으면 빠뜨린 것이다.

| 수단 | 어떤 쓰기에 | 사용처 |
|---|---|---|
| **DB UNIQUE + 중복은 무시하고 성공** | 삽입형 | `client_event_id`(02 D35) · 장바구니 `option_key` uk(02 D44) · `uk_address_default` |
| **조건부 UPDATE** — 1행 매치 = 내가 바꾼 것, 0행 = 이미 남이 함 | 상태 전이형 | I-30 발송 · 클레임 신청 · 재고 차감(02 D33) |
| **이미 그 상태면 성공 반환** | 발급·승계형 | CH-1 세션 발급(노션 CH-1 2026-07-31) · 세션 claim 승계(07 §3-2) |
| **`SETNX` / dedupKey** | 조정·통지형 | I-21 목록 저장 · I-20 종료 통지(`session-end:{userId}:{sessionId}`, 중복은 `202 duplicate`) |

- **`INSERT IGNORE` 금지 (02 D35)** — 중복 말고 다른 오류까지 삼킨다. 삽입 전 중복을 걸러내거나(`BehaviorEventAppender.dropKnownDuplicates`), 제약 위반 예외를 잡아 멱등 통과시킨다.
- **동시 도착은 "검사 → 삽입" 사이를 빠져나가 제약에서 갈린다.** 그래서 `DataIntegrityViolationException`을 잡아 성공으로 돌리는 건 예외 처리가 아니라 **정상 경로**다(`RecommendationListService` — 경합을 warn 로그로만 남기고 통과).
- **멱등하지 않기로 한 곳은 근거를 적는다** — C-4 장바구니 삭제는 삭제 연타의 두 번째가 404이고, *그래서* 삭제 이벤트를 성공(200)에만 적재한다(노션 C-4·I-24). 404까지 세면 이벤트가 부풀려진다는 게 근거다.
- **현재 확인 필요**: `POST /internal/seller/{brandId}/products`(상품 생성)에 멱등 장치가 보이지 않는다 — 중복 호출 시 상품이 2건 생길 수 있고, "HITL confirm 뒤에만 호출된다"는 전제에만 기대고 있다. **판정 대상.**

**캐시** — `07-redis-design.md` §1을 따른다. 요약: TTL 없는 키 금지 · 사본(캐시)은 fail-open·원본(세션·락·카운터)은 fail-fast · 커밋 **전** 캐시 삭제 금지 · `KEYS` 금지.

**테스트 (2026-08-07 신설)**

- **손으로 쓴 쿼리(`@Query`)는 DB에 붙는 테스트로 검증한다.** 목(mock)은 "이 메서드를 불렀다"만 확인하므로 JPQL이 올바른 행을 가져오는지는 검증되지 않는다. *현황: DB에 붙는 테스트 0개인데 `OrderItemRepository` 445줄·`BehaviorEventRepository` 330줄·`ProductRepository` 250줄이 쿼리 덩어리다 — 미해결.*
- **도메인마다 서비스 테스트가 있어야 한다.** *현황: `review`가 작성 경로 하나뿐(조회·신고 미커버) — 미해결.*

**공통**
- 의존성은 생성자 주입만(`@RequiredArgsConstructor`), 필드 `@Autowired` 금지.
- 조회 네이밍: 없으면 예외를 던짐 → `getXxx`, 없을 수 있음(Optional) → `findXxx`. 혼용 금지.

**Spring Security 구현 규약**
- **필터에서 터진 401/403도 envelope로.** 필터 체인은 `GlobalExceptionHandler`(디스패처 서블릿 안) 바깥이라 그냥 두면 스프링 기본 응답이 나간다 → `AuthenticationEntryPoint`(401)/`AccessDeniedHandler`(403)가 직접 envelope JSON을 쓴다. D2의 401 2종 분리는 JWT 필터가 부재/만료를 구분해 request attribute로 넘기고 EntryPoint가 `AUTH_REQUIRED`/`AUTH_TOKEN_EXPIRED`로 분기.
- **`SessionCreationPolicy.STATELESS` + csrf/formLogin/httpBasic disable**(JWT·세션 미사용). 단 **RT 쿠키(refresh·logout)는 CSRF 표면이 남는다** → RT 쿠키에 `SameSite=Strict` 부여로 봉쇄(FE·BE 동일 사이트라 부작용 없음, D3의 `Path=/api/auth` 최소화와 세트).
- **인증 선택 경로**(E-1 등 "permitAll이지만 JWT 있으면 검증"): JWT 필터는 permitAll 여부와 무관하게 "토큰 없으면 통과, 있으면 파싱해 SecurityContext 세팅, 파싱 실패 시 실패 처리"로 동작하는 구조로 짠다.
- **만료 토큰의 게스트 강등 차단**(2026-08-07): 위 구조는 permitAll 경로에서 만료 토큰을 **조용히 통과**시킨다. 대부분은 그게 맞지만 **신원에 따라 응답이 달라지는 게스트 허용 경로**(`/api/cart`·`/api/chat/`·`/api/events`)에서는 회원이 게스트로 강등돼 *빈 장바구니를 자기 것으로 보고*, 200이라 FE가 재발급 기회조차 얻지 못한다 → 이 세 접두사에 한해 필터가 그 자리에서 `AUTH_TOKEN_EXPIRED`를 쓴다(EntryPoint 재사용 — envelope·401 2종 분기가 갈라지지 않게).
  - **공개 카탈로그는 제외** — 로그인 여부로 내용이 같은데, 쿠키 수명(14일) > 토큰 수명(30분)이라 오래 방치한 브라우저엔 만료 쿠키가 남아 있다. 401을 내면 RT까지 만료된 사람은 재발급도 실패해 **구경만 하러 온 사용자가 로그인 화면으로 튕긴다.**
  - **`/api/auth/**`도 제외** — 만료 AT 쿠키는 `Path=/`라 재발급·로그아웃·재로그인 요청에 그대로 실린다. 끊으면 A-4가 자기를 막아 무한 루프가 되고(04 A-4) 재로그인 경로가 사라진다.
  - **변조 토큰은 종전대로 통과**(게스트 취급) — 재발급으로 고쳐지지 않아 401을 내도 사용자가 할 수 있는 게 없다.
- **account_event_logs 적재 지점**: A-2 로그인은 formLogin이 아니라 컨트롤러 방식(AuthenticationManager 직접 호출)이라 Security Success/FailureHandler가 자동 발화하지 않음 → **AuthService의 성공/실패 지점에서 직접 적재**(02 D32의 "핸들러에 심음"을 이 지점으로 구체화 — 02에 주석 반영).

**JPA 구현 규약**
- **`spring.jpa.open-in-view: false`** — 지연 로딩은 트랜잭션(서비스) 안에서 끝낸다. 기본값(on)은 커넥션을 응답 렌더링까지 점유하고 LazyInitializationException을 컨트롤러에서 만나게 함.
- **`ddl-auto: validate`** — 스키마 원본은 02/schema.sql(사람이 적용·리뷰), JPA는 스키마를 만들지도 바꾸지도 않는다. 로컬 최초 기동은 schema.sql 수동 적용(또는 compose init 스크립트).
- 전역 LAZY(Entity 항) 전제에서 **목록 조회의 N+1은 fetch join/`@EntityGraph`로 해소** — 리뷰 기준: 쿼리 로그에서 같은 SELECT가 행 수만큼 반복되면 N+1.
- 수정은 "조회 → 엔티티 메서드 호출 → 커밋 시 더티체킹". `save()` 재호출로 UPDATE하지 않는다.
- ID 전략은 `IDENTITY`(MariaDB auto_increment 정합). Hibernate 배치 INSERT 제약은 감수 — 대량 시드는 JdbcTemplate batch 허용(§4와 일관).

### 3-2. 판정 기준 — 원칙이 아니라 도구 (2026-08-07 신설)

> §3·§3-1이 "무엇을 지키나"라면 여기는 **"애매할 때 어떻게 판정하나"**다. 규칙이 아니라 재는 자다.

**응집도는 높게, 결합도는 낮게.** 응집도 = 한 클래스 *안*의 것들이 서로 관련 있나(높아야 좋음). 결합도 = 클래스 *사이*가 얼마나 얽혔나(낮아야 좋음). **둘은 서로 민다** — 쪼갤수록 응집도는 오르지만 클래스가 늘어 결합도도 오른다. 그래서 판단은 언제나 **"쪼갰을 때 응집도 이득 > 결합도 손해인가"** 하나다.

**클래스를 쪼갤지 판정하는 3단계** — 순서를 지킨다. 2단계가 결론이고 나머지는 힌트다.

1. **소비자가 갈리나** (힌트) — 서로 다른 호출자 그룹이 서로 다른 메서드만 쓰면 의심한다.
2. **안에서 덩어리가 갈리나** (**결론**) — 필드·private 헬퍼를 어느 메서드가 쓰는지 그려본다. 두 덩어리가 **아무것도 공유하지 않으면** 쪼갠다. 공유하면 쪼개도 복사되거나 서로 부르게 되므로 **그냥 둔다.**
3. **git 기록** (참고) — `git log -- <파일>`로 "무엇 때문에 고쳐졌나"를 본다. 이유가 두 갈래로 반복되면 1·2단계를 더 진지하게 본다.

*적용 사례 (2026-08-07)*

| 대상 | 1단계 | 2단계 | 판정 |
|---|---|---|---|
| 재고 → `ProductStockService` | 갈림 | **안 겹침** — 조회 헬퍼를 하나도 안 씀 | **쪼갬** (PR #123) |
| `ProductService`(376줄) | 갈림 (화면 / LLM / 내부) | **겹침** — `toCards`·`parseJson`·`popularIds`가 그룹을 가로지름 | **안 쪼갬** |

`ProductService`는 git 기록상 LLM 요청 5회 · 화면 요청 4회로 갈려 있어 처음엔 분리 후보였으나, 2단계에서 공용 배관이 드러나 보류했다. **파일 길이는 판정 근거가 아니다** — 305줄 `CartService`는 이유가 하나라 그대로 두고, 376줄 `ProductService`도 덩어리가 하나라 그대로 둔다.

## 4. 기술 스택 & 버전

| 항목 | 선택 | 근거 |
|---|---|---|
| Java | 21 (Microsoft OpenJDK, JAVA_HOME 명시 필수) | 로컬 환경 제약 (CLAUDE.md) |
| Spring Boot | 3.5.x | Java 21 대응 최신 안정 |
| 빌드 | Gradle wrapper (`./gradlew`) | |
| DB | MariaDB 11.x (로컬 docker-compose) | Hibernate `MariaDBDialect` + MariaDB Connector/J(`jdbc:mariadb://`). 포트 3306·utf8mb4 동일 |
| ORM | Spring Data JPA + Hibernate. 복잡 집계(판매자 지표)만 JdbcTemplate 네이티브 쿼리 허용 | QueryDSL 미도입 — 동적 쿼리가 검색 1곳뿐이라 도입 비용>효용 |
| Redis | spring-data-redis — 채팅 세션 TTL·캐시·토큰 무효화·레이트리밋 | 무엇을 왜 담는지는 [07 Redis 설계](07-redis-design.md)가 원본 |
| 분산 락 | ShedLock (Redis 기반) — 스케줄러 틱당 1대만 실행 (01 §6, D-분산5) | 단일 인스턴스에서도 무해 |
| 인증 | spring-security + jjwt (OAuth 제외로 oauth2-client 미도입) | |
| 문서화 | API 명세는 04 문서(노션)가 원본 — springdoc/Swagger 미도입 (팀 전원 미사용 + 운영 노출 회피, 2026-07-23 결정) | |

## 5. 설정/환경변수 규약

`application.yml`(공통) + `application-local.yml`(gitignore) 프로파일. 시크릿은 전부 환경변수 참조:

| 키 | 용도 |
|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | MariaDB |
| `REDIS_HOST` / `REDIS_PORT` | Redis |
| `JWT_SECRET` | AT/RT 서명 (HS256, Spring 내부 검증) |
| `STREAM_TICKET_PRIVATE_KEY` / `..._KID` | 스트림 티켓 **RS256** 서명 private key + 현재 키 ID(`kid`) — **Spring만 보관·회전**, public key는 JWKS(`/.well-known/jwks.json`)로 노출(D5) |
| `LLM_BASE_URL` | FastAPI 주소 — **Spring→FastAPI 호출용**(P-5 추천·세션 정리 통지). 채팅 SSE는 FE 직결이라 무관 |
| `NEXT_PUBLIC_LLM_SSE_URL` (FE) | 채팅 SSE 직결용 FastAPI 공개 URL — **FE 브라우저가 티켓 들고 직접 연결**(FE 팀 공유 필요, D5) |
| `INTERNAL_API_TOKEN` | internal API 서비스 토큰 (FastAPI와 동일 이름·값 org 공유) |
| `app.mock.delivery-minutes` 등 | mock 전이 간격 (환경변수 아님, yml 기본값 delivery/confirm/claim-approve = 5/10/5분 — 01 §6). **구 `shipping-minutes`는 2026-08-06 제거** — `ORDERED→SHIPPING`이 판매자 발송(I-30) 소관이 되면서 스케줄러가 더는 일으키지 않는다(01 D4 개정) |

`.gitignore`에 `application-local.yml`, `.env` 포함 확인. 어떤 시크릿도 커밋 금지.

- **타임존**: JVM과 MariaDB 세션 모두 `Asia/Seoul` 고정 — orderNo 날짜 파생(02 D24)·mock 간격 계산·시연 중 시각 표시가 전부 이 기준.
- **CORS**: 배포는 nginx 동일 오리진이라 불필요. 로컬 개발(FE 3000 → BE 8080 직행)만 local 프로파일에서 `http://localhost:3000` 허용.

## 6. 공통 규약 요약 (구현 세션 체크용)

- [ ] 모든 컨트롤러 응답이 `ApiResponse<T>` envelope인가
- [ ] 예외는 도메인별 커스텀 예외 → `GlobalExceptionHandler`에서 ErrorCode 매핑인가 (컨트롤러 try-catch 금지)
- [ ] 상태 전이·자격 검증(01 문서 매트릭스)이 서비스 레이어에 있는가
- [ ] `/internal/**`에 서비스 토큰 필터가 걸려 있고, FE 경로에서 접근 불가한가
- [ ] Spring→FastAPI 호출(P-5 추천·세션 정리)에 타임아웃이 있는가 (P-5 연결 2s/응답 3s — 채팅 60s는 직결이라 Spring 소관 아님)
- [ ] 스트림 티켓이 **RS256**으로 서명되고 private key는 env/keystore에만 있는가(JWKS로 public만 노출), 신원(userId/guestId/brandId)은 **서버가 채워** 티켓 claim에 박히는가(클라이언트 주장 무시)
- [ ] 추천 목록 조회(`GET /api/chat/lists/{listId}` CH-5)가 다건 `id IN` 조회에 인덱스를 타는가, HIDDEN/품절을 드롭하는가
- [ ] 필터발 401/403이 envelope 형식인가 (EntryPoint/AccessDeniedHandler — §3-1), 401 2종이 구분되는가
- [ ] `open-in-view: false`·`ddl-auto: validate`인가 (§3-1)
- [ ] 엔티티에 `@Setter`가 없고, 엔티티→응답 변환이 `XxxResponse.from()`에 모여 있는가 (§3-1)
- [ ] 시크릿이 코드·yml에 리터럴로 없는가
- [ ] 배포 compose에서 nginx만 `ports:` publish이고 나머지는 `expose`인가 (spring 8080 외부 노출 = nginx 우회 뒷문)
- [ ] internal 컨트롤러가 도메인 서비스를 재사용하는가 (로직 복제 금지)
- [ ] 남의 도메인 Repository를 **쓰기**로 쓰지 않는가 (읽기는 허용 — §3-1). 상태·수량 변경이 단일 관문을 지나는가
- [ ] **쓰기 트랜잭션 안에서 HTTP·Redis를 기다리지 않는가** (§3-1 — 커넥션 풀 고갈)
- [ ] **쓰기 입구가 "두 번 오면?"에 답하는가** — `/internal` 전건·스케줄러·FE 배치. 멱등 장치가 있거나, 없다면 그 이유가 적혀 있는가 (§3-1 멱등성)
- [ ] `INSERT IGNORE`를 쓰지 않았는가. 중복 경합의 제약 위반 예외를 **성공으로** 돌리는가 (§3-1 멱등성)
- [ ] 파일 이름이 정체를 드러내는가 — 엔티티=명사 / enum=`~Status`·`~Type` / 부품=`~er` / 층=접미사 (§3)
- [ ] 둘 이상의 도메인이 쓰는 설정·상수가 `global/`에 있는가 (§3 — 패키지 순환의 원인)
- [ ] 구현이 하나뿐인 인터페이스를 새로 만들지 않았는가, 도메인 클래스끼리 상속하지 않았는가 (§3-1 OOP)

## 7. 요청 시퀀스

요청을 두 축으로 나눈다: **누가 시작하나**(유저 직접 `/api`(JWT) vs 에이전트 경유 chat→SSE→`/internal`) × **부수효과**(읽기 vs 쓰기). 판매자는 새 패턴이 아니라 여기에 **소유권 검사(본인 브랜드인가) + 집계-only**가 한 겹 더 얹힌 형태.

```
               읽기 (조회)                     쓰기 (부수효과)
유저 /api    ① 상품·마이페이지 조회          ② 담기·주문·클레임·후기·찜·문의
에이전트     ③ 추천(I-1 콜백)               ④ 담기까지만(I-2 콜백+action)
판매자       ⑤ 대시보드(S-1/2)+소유권       ⑥ 상품수정(S-3)+소유권  ⑦ 판매자챗봇(S-4→I-6)
```

- **경계(일부러 빈 칸)**: 에이전트의 쓰기 상한은 "담기까지". 주문 생성·클레임·후기는 LLM이 못 한다(05 §0-1). 고도화 시에도 초안+사용자 본인 JWT 확인으로만.
- **핵심 비대칭**: ①②(유저)는 1왕복. ③④(에이전트)는 **SSE를 FastAPI가 직접 붙들고(D5 직결)**, 그 안에서 FastAPI가 Spring `/internal`을 여러 번 콜백한다(추천은 후보조회→Top5 하이드레이션 2왕복). Spring은 더 이상 스트림 소켓을 릴레이하지 않고, **티켓 발급자(진입 시) + `/internal` 피호출자(스트림 중)** 역할만 한다.
- **입구는 둘, 로직은 하나**: ④의 담기가 ②와 **같은 `CartService.addItem`을 재사용**(§3). `/api`·`/internal`은 신뢰 모델만 다른 입구. 담기 주체는 회원(JWT의 userId) 또는 게스트(guest_id 쿠키/메아리 — 02 D30).
- **판매자 불변식**: `brandId`는 항상 **서버가 계정에서 유도**(사용자·LLM이 주장 못 함), 에이전트에는 **집계된 값만** 준다(raw 접근 없음 — I-6).

### 줄글 설명

**① 유저 직접 조회** — FE `GET /api/products` → nginx → Spring: 시큐리티 필터(상품조회는 permitAll 통과) → 컨트롤러 → 서비스 → 리포지토리 → MariaDB → envelope 응답. FastAPI 무관.

**② 유저 직접 쓰기(담기)** — FE `POST /api/cart/items`(AT 쿠키, 게스트는 guest_id 쿠키 — 02 D30) → 시큐리티 필터가 JWT 검증해 userId 확정(게스트는 쿠키가 주체) → CartController → **CartService.addItem** 검증(상품·옵션·수량 — 재고 차감은 결제 성공 시점, 02 D33) → INSERT → cartItemId envelope. (`add_to_cart` 행동 이벤트는 서버가 아니라 **FE가 성공 콜백에서 E-1로 전송** — 02 D31)

**③ 에이전트 조회 추천 (직결 + 2왕복 리랭킹, D5·05 §1)** — FE가 `POST /api/chat/sessions`(CH-1)로 세션과 함께 **스트림 티켓**을 받고(Spring이 신원 검증 후 RS256 서명·발급) → FE가 그 티켓으로 **FastAPI에 직접 SSE 연결** → FastAPI가 발화에서 **정형조건(가격·카테고리·색상·재고·판매상태)** 과 **의미조건(원룸에 적합·공부하기 좋은…)** 을 추출 →
  - **[1왕복 · 후보 조회]** 정형조건만 `GET /internal/products/search` 콜백 → InternalController가 ProductService 재사용해 MariaDB에서 후보 조회. **정형 진실(가격·재고·상태)은 여기서 확정** — 이후 벡터DB가 낡아도 살 수 없는 상품이 안 섞임. 응답은 **리랭킹용 최소필드(productId·name·summary·attributes·tags)**, **라운드1 LIMIT 상한**으로 후보 폭발(느슨한 대분류) 방지.
  - **[리랭킹]** FastAPI가 **자기 소유 벡터DB(productId·attributes·embedding)** 로 의미조건 리랭킹 → **top-K(20~30)만** LLM에 태워 추천 이유·채팅 응답 생성 → 확정 목록 선정 — **목록당 최대 9개**(하이드레이션에서 재고/HIDDEN 드롭 대비 넉넉히 고름).
  - **[목록 저장 콜백]** FastAPI가 목록 확정 후 `POST /internal/recommendations`(I-21 — sessionId·recommendationRequestId·listType·lists[{listId, productIds 순서 유지}], **목록당 9개·목록 최대 10개**) 콜백 → Spring이 **Redis(10분, CH-5 조회 전용) + DB(영구, 정본)** 양쪽 저장 + `recommendation_generated` 적재(**적재는 잔여 구현** — 06 Phase 5). **콜백 성공 후에만** SSE `products.ready` 발행(실패 시 발행 금지 — FE가 빈 목록을 조회하지 않게). *스키마 확정 2026-07-28~30*
  - **[SSE 발행]** `token`(응답 텍스트)·`conditions`(칩)·`suggestions`(해당 시)·`products.ready{listIds[]}`(**상관키만**, 카드 필드 없음)·`done`.
  - **[2왕복 · FE pull 하이드레이션]** FE가 `products.ready` 수신을 **트리거**로 `GET /api/chat/lists/{listId}`(CH-5, Spring) 호출 → 카드 필드(가격·정가·썸네일·재고·평점·reviewCount)를 **BE 자기 DB에서** 받아 순서 그대로 우측 패널 렌더. 추천 이유는 **이원화**(2026-07-18) — 채팅 말풍선용은 SSE로, **추천 카드용 `reason`은 CH-5 응답에 포함**(I-21 `reasons` echo, 없으면 null). (구 `products{id,reason}` + P-7 하이드레이션 방식은 이것으로 대체 — P-7은 2026-07-28 폐기(04). SSE는 단방향이라 "결과 준비됨" 신호도 같은 열린 소켓의 이벤트로 전달 — FE는 폴링하지 않음.)
  - 게스트면 티켓 `sub_type:guest`, 개인화 없이 동일 흐름. **SELLER 채널**은 변종(brandId는 서버 유도, I-6 사용).

**④ 에이전트 쓰기(담기)** — ③처럼 FastAPI가 SSE를 붙든 채 상품·옵션·수량 확정 후 `POST /internal/cart/items` 콜백(`X-Internal-Token`, userId/guestId는 **티켓 `sub`의 메아리**) → InternalController가 **②와 같은 CartService.addItem** 호출 → 결과 3갈래: 성공→cartItemId→`action{CART_ADDED}` (게스트도 guestId로 담기 성공 — 02 D30, 로그인 유도는 결제 시점); 옵션필요→400 `CART_OPTION_REQUIRED`+options→"어떤 색?" 되물음; 그 외 검증 실패→사유 안내. 자동 재시도 없음(중복 담기 방지).

**⑤ 판매자 조회(대시보드)** — FE `GET /api/seller/summary`(AT 쿠키) → 시큐리티 필터가 JWT+`SELLER` 확인 → SellerController가 **토큰 memberId에서 brandId 유도**(주장받지 않음) → SellerService 집계(매출·주문수는 order_item, 조회/담김수는 behavior_events의 product_view·add_to_cart — 02 D31; 복잡 집계만 JdbcTemplate) → WHERE에 brandId 박혀 남의 데이터는 쿼리 단계에서 안 나옴 → envelope. (S-2도 동형.)

**⑥ 판매자 쓰기(상품수정/삭제)** — 판매자 직접 경로는 없다(구 S-5 폐기 2026-07-21). 챗봇 HITL만: FastAPI가 confirm된 draft를 `PATCH /internal/seller/{brandId}/products/{productId}`(I-11)·`DELETE`(I-12) 콜백(`X-Internal-Token`, brandId는 **티켓 claim의 메아리**) → SellerProductService가 **먼저 소유권 검사**(상품의 브랜드 == 티켓 brandId, 아니면 **404로 존재 은닉** — productId가 LLM 값) → UPDATE(`status=HIDDEN` 비노출 포함). ②엔 없던 소유권 스텝이 결정적.

**⑦ 판매자 에이전트(챗봇)** — FE가 `POST /api/chat/seller/sessions`(S-4)로 JWT+`SELLER`+세션 검증 후 **SELLER 스코프 스트림 티켓**을 받아(brandId는 **서버가 계정에서 유도**해 티켓 claim에 박음 — 클라이언트/LLM 주장 불가) → FE가 티켓으로 FastAPI에 직접 SSE 연결(`channel:SELLER`) → 분석에 수치 필요 시 `GET /internal/seller/{brandId}/sales` 등 집계 콜백(I-6~I-16 — brandId는 **티켓 claim의 메아리**, FastAPI 툴 인자 아님) → InternalController가 ⑤와 같은 집계 서비스로 **집계값만** 반환(raw 로그·임의 쿼리 권한 없음 → text2SQL 실패·타 판매자 접근 원천 차단) → FastAPI가 분석 답변 `token`(+차트용 구조화 데이터) SSE 발행. ※ **판매자용 구조화 이벤트(예: `stats`) 스키마는 05에 미정 — LLM 팀 합의 필요(05 §4).**

## 8. SSE 성능·안정성 지형 (결정됨 vs 열림)

직결(D5) 전환으로 SSE의 성능·안정성 숙제는 **Spring 프로세스 밖 = FastAPI 소유**로 이동했다. Spring은 티켓 발급자 + `/internal` 피호출자일 뿐 스트림 소켓을 붙들지 않으므로, 아래 "열림" 항목은 **FastAPI(LLM 팀) 숙제**다. Spring 측 SSE 자원·수명 관리(과거 `SseEmitter`/WebClient 브리지)는 **폐기**됐다.

> **[2026-07-16 갱신]** 이전 서술(Spring 중개 전제)은 D5 직결 확정으로 폐기. "네트워크 장비와의 충돌"(§1-2 D-분산6)은 이제 ALB/공개 nginx ↔ FastAPI 사이에 적용된다.

**결정됨**
- 버퍼링(스트리밍 경로만 `proxy_buffering off`+`X-Accel-Buffering:no`), idle timeout(하트비트 `: ping`+장비 300s), 분산 라우팅(self-pinning, sticky 불필요), FastAPI 다운(SSE `error` `LLM_UNAVAILABLE`, 비채팅 정상) — 전부 D-분산6/D5.

**열림 (FastAPI 소유 설계 항목 — LLM 팀)**
- **스트림 수명·하트비트 정합**: 하트비트 간격 vs 스트림 최대 수명 — 하트비트가 살아있는데 죽으면 모순. 하트비트 기준으로 정의.
- **클라이언트 이탈 시 LLM 생성 취소**: FE가 탭 닫으면 FastAPI가 이탈을 감지해 **진행 중인 LLM 생성을 취소**해야 함 — 안 하면 아무도 안 보는데 LLM 비용이 계속 나간다(성능+비용).
- **동시 스트림 상한**: FastAPI 1대(D-분산8)의 async 이벤트 루프가 감당하는 동시 SSE 수. 채팅은 외부 LLM 대기(I/O)가 대부분이라 한 대가 다수 감당하지만 천장은 존재 → 넘으면 `LLM_UNAVAILABLE` degrade + rate limit(05 §3).
- **느린 클라이언트/백프레셔**: FastAPI가 빨리 뱉는데 클라가 느리면 FastAPI 메모리에 적체 → 상한·드롭 정책 필요.

> **BE 측 남는 숙제**: 티켓 발급 엔드포인트(04)의 발급 지연·키 회전, 추천 목록 조회(CH-5)의 배치 조회 성능(다건 id IN 조회 인덱스).
