# mock FastAPI (Phase 5 검증용)

LLM 팀 FastAPI가 없을 때 **FE 직결 흐름**(티켓 → JWKS 검증 → I-21 콜백 → `products.ready` → CH-5)을
검증하기 위한 스텁이다 (06 Phase 5). 실제 LLM 응답 없이 고정 SSE를 반환한다.

## 실행

```bash
cd mock-fastapi
pip install -r requirements.txt
# Spring(8080)이 떠 있어야 JWKS 조회가 된다
INTERNAL_API_TOKEN=<backend의 app.internal.token 값> uvicorn main:app --port 8000
```

환경변수: `SPRING_BASE_URL`(기본 http://localhost:8080), `INTERNAL_API_TOKEN`(**필수** — backend application-local.yml의 app.internal.token과 동일해야 I-21 콜백이 통과).

## 흐름 확인 (05 §1-2-1)

```bash
# 1) CH-1 — 세션+티켓 발급 (게스트면 쿠키 자동 발급)
curl -s -X POST localhost:8080/api/chat/sessions -H "Content-Type: application/json" -d '{"channel":"SHOPPING"}'
# 2) 티켓으로 mock FastAPI에 직결 — SSE로 token/conditions/products.ready{listId}/done 수신
curl -sN -X POST localhost:8000/chat -H "Authorization: Bearer <streamTicket>" \
  -H "Content-Type: application/json" -d '{"sessionId":"<sessionId>","channel":"SHOPPING","message":"추천해줘"}'
# 3) CH-5 — listId로 카드 조립
curl -s localhost:8080/api/chat/lists/<listId>
```
