# 판매자 업로드 이미지를 상품 경로 규약으로 옮긴다 (2026-08-10)

S-6이 발급하는 업로드 키를 `products/uploads/{uuid}.{ext}`에서 `products/temp/{uuid}.{ext}`로 옮기고,
상품 등록(I-10)·수정(I-11) 시 서버가 `products/{productId}/main.{ext}`로 복사해 그 최종 주소를 저장한다.

## 왜 하는가

크롤링 상품 6만여 건은 이미 `products/{productId}/main.{ext}` 규약을 쓴다(상세 이미지는
`products/{productId}/detail/{000..N}.{ext}`). **판매자 등록분만 이 규약 밖**에 있었다. 등록 시점에
`productId`가 없어서 별도 prefix를 쓸 수밖에 없었기 때문이다.

2026-08-09에는 `temp/`라는 이름을 의도적으로 기각했다 — `s3:DeleteObject`가 없어 파일을 옮기거나
지우는 단계를 아예 둘 수 없었고, "임시"라는 이름의 자리에 영구 데이터가 남으면 나중에 정리 대상으로
오인될 위험이 있었다. **2026-08-10에 그 전제가 무너졌다**: 인프라가 `products/temp/`에 1일 만료
라이프사이클을 걸기로 했다. 지우는 주체가 우리가 아니게 되므로 삭제 권한 없이도 "옮기기"가 성립한다.

## 흐름

```
① S-6        key = products/temp/{uuid}.{ext}  ← 설정값(key-prefix)만 변경
② 브라우저    PUT (변화 없음)
③ I-10/I-11  product INSERT/UPDATE → productId 확보
④ 서버        CopyObject: products/temp/{uuid}.jpg → products/{productId}/main.jpg
⑤ 저장        image_url = https://.../products/{productId}/main.jpg?v={epoch}
⑥ 원본        지우지 않는다. 24시간 뒤 라이프사이클이 소멸시킨다
```

## 결정과 근거

### productId를 선점하지 않고 복사한다

"상품을 먼저 만들어 `productId`를 받고 그 자리에 바로 업로드"하면 복사가 아예 필요 없다. 그런데
**I-10이 1회 호출에서 2단계로 바뀐다** — 계약 변경의 무게가 S-6 문구 수정과 비교가 안 되고, 등록 도중
이탈하면 이미지 없는 유령 상품 행이 남는다(파일 고아를 없애려다 DB 고아를 만든다). 복사 방식은 FE·AI
호출 방식이 하나도 안 바뀐다.

### `?v={epoch}`를 붙인다

이미지를 교체해도 키가 같아 URL이 한 글자도 안 바뀐다. 브라우저와 AI 서버가 옛 이미지를 계속 보게
되고 오류는 나지 않는다. `image_url`은 전체 URL을 저장하는 자유로운 문자열이라 쿼리를 붙이는 데
비용이 없다(시드의 11번가 URL도 `B.jpg?458000000` 형태로 같은 관행을 쓴다).

### 복사 실패는 롤백한다

임시 주소를 그대로 저장하는 폴백은 두지 않는다 — 하루 뒤 이미지만 조용히 죽는 시한폭탄이 된다.
트랜잭션을 롤백하고 500을 낸다. 임시 파일은 24시간 살아 있으므로 판매자는 재업로드 없이 등록만
다시 시도하면 된다. S3 호출이 DB 트랜잭션 안에 들어가지만(수백 ms 홀딩) 판매자 등록은 트래픽이
없는 경로라 감수한다.

### 임시 주소일 때만 복사한다

들어온 `imageUrl`이 우리 temp prefix로 시작할 때만 복사한다. 이미 최종 주소이거나(수정 시 이미지를
안 바꾼 경우) 플레이스홀더·외부 주소면 그대로 둔다. 없으면 가격만 고치는 I-11 호출이 매번 S3를 때린다.

## 변경 지점

| 위치 | 변경 |
|---|---|
| `application.yml` | `app.s3.key-prefix` → `products/temp` |
| `SellerConfig` | `S3Client` 빈 추가 (지금은 `S3Presigner`만 있고 실제 S3 호출을 하지 않는다) |
| **신규** `ProductImageStorage` | temp 판별 · CopyObject · 최종 URL 조립. S3 지식을 여기 가둔다 |
| `SellerProductService.create/update` | 복사 호출 + `changeImageUrl` |
| `SellerImageUploadService` | 응답 의미 주석 수정 |

`SellerProductService`가 AWS SDK를 직접 알지 않게 storage 클래스로 한 겹 둔다.

## 외부 의존 (인프라, 2026-08-10 합의 완료)

- `products/temp/`에 1일 만료 라이프사이클 — 24시간 보장, 실제 삭제 24~48시간
- IAM 역할 `jarvis-backend-s3-upload`에 `s3:GetObject` 추가 (복사 원본 읽기)
- `products/` 하위에는 만료 규칙을 걸지 않는다 — 걸면 상품 이미지 6만여 장이 사라진다
- CORS는 이미 적용돼 있다 (`PUT`·`GET`·`HEAD` / `narvis.shop`·`localhost:3000`, 08-09 갱신)

## 레인 영향

- **FE** — 코드 변경 없음. 등록 후 임시 URL을 미리보기로 계속 물고 있으면 하루 뒤 깨지므로
  S-3·P-2로 재조회한 주소를 쓴다.
- **LLM** — 호출 방식 무변경. 🔴 S-6이 준 URL을 저장하면 안 된다
  (`jarvis-ai/app/agents/seller/draft_session.py`의 `image_urls`가 실제 위험 지점).
  보낸 값과 저장된 값이 다르다는 점도 통보 대상.
- 통보는 자료실 「🧩 API 명세와의 정합 요구사항」에 누적했다.

## 남은 위험

`products/{productId}/main.{ext}`가 크롤링 상품과 같은 공간을 쓴다. 판매자 등록 상품은
AUTO_INCREMENT가 크롤링 id 최댓값 위에서 시작하므로 충돌하지 않지만, 카탈로그를 명시적 id로
재적재하면 겹칠 수 있다.

이미지 교체 시 확장자가 바뀌면 `main.jpg`와 `main.webp`가 둘 다 남는다. `image_url`이 새것을
가리키니 동작은 정상이고, 삭제 권한이 없어 옛 파일은 방치된다.

## 정본

노션 📡 API 명세서 **S-6 · I-10 · I-11**(2026-08-10 개정, 노란색 표시).
내부 문서 `docs/backend/04-api-spec.md` §7 S-6 · `05-llm-contract.md` I-10·I-11도 맞춰뒀다.
