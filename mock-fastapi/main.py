"""mock FastAPI (06 Phase 5) — FastAPI 미기동 시 FE 직결 흐름 검증용 스텁.

실제 LLM 팀 서비스가 하는 일 중 계약 경계만 흉내 낸다:
  1. 스트림 티켓을 Spring JWKS(RS256)로 검증 (05 §1-0)
  2. I-1 후보 조회 → Top5 선정(여기선 상위 N 그대로)
  3. I-21 콜백으로 Spring에 목록 저장 → 성공 후에만 SSE products.ready{listId} 발행 (05 §1-2-1)
  4. I-20 세션 종료 통지 수신(멱등 200)

실행: uvicorn main:app --port 8000  (환경변수: SPRING_BASE_URL, INTERNAL_TOKEN)
"""

import json
import os
import uuid

import httpx
import jwt
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse
from jwt import PyJWKClient

SPRING_BASE = os.getenv("SPRING_BASE_URL", "http://localhost:8080")
INTERNAL_TOKEN = os.getenv("INTERNAL_TOKEN", "")  # backend application-local.yml의 app.internal.token과 동일하게 설정
ISSUER = "jarvis-spring-auth"
AUDIENCE = "jarvis-fastapi-ai"

app = FastAPI(title="jarvis mock-fastapi")
jwk_client = PyJWKClient(f"{SPRING_BASE}/.well-known/jwks.json")


def verify_ticket(authorization: str | None) -> dict:
    """JWKS로 signature/exp/iss/aud/scope 검증 — 신원을 만들지 않고 티켓에서 취한다 (05 §1-0)."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="stream ticket required")
    token = authorization.split(" ", 1)[1]
    try:
        key = jwk_client.get_signing_key_from_jwt(token).key
        claims = jwt.decode(token, key, algorithms=["RS256"], audience=AUDIENCE, issuer=ISSUER)
    except jwt.PyJWTError as e:
        raise HTTPException(status_code=401, detail=f"invalid ticket: {e}")
    if claims.get("scope") != "chat:stream":
        raise HTTPException(status_code=401, detail="invalid scope")
    return claims


def sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@app.post("/chat")
async def chat(request: Request):
    claims = verify_ticket(request.headers.get("authorization"))
    body = await request.json()
    session_id = body["sessionId"]

    async def stream():
        yield sse("token", {"text": f"({claims['sub_type']}:{claims['sub']}) 추천을 준비하고 있어요. "})
        yield sse("conditions", {"items": ["mock", "고정 응답"]})
        headers = {"X-Internal-Token": INTERNAL_TOKEN}
        async with httpx.AsyncClient(timeout=3) as client:
            # [1왕복] I-1 후보 조회 — 넉넉히 7개 (CH-5 드롭 대비, 05 §1-2-1)
            search = await client.get(f"{SPRING_BASE}/internal/products/search",
                                      params={"size": 7}, headers=headers)
            product_ids = [item["productId"] for item in search.json().get("data") or []]
            if not product_ids:
                yield sse("done", {"finishReason": "zero_result"})
                return
            # [콜백] I-21 저장 성공 후에만 products.ready 발행 (05 §1-2-1)
            list_id = str(uuid.uuid4())
            callback = await client.post(f"{SPRING_BASE}/internal/recommendations",
                                         json={"sessionId": session_id, "listId": list_id,
                                               "productIds": product_ids[:5]},
                                         headers=headers)
            if callback.status_code == 200 and callback.json().get("success"):
                yield sse("products.ready", {"listId": list_id})
            else:
                yield sse("error", {"code": "LLM_TIMEOUT", "message": "추천 목록 저장에 실패했어요"})
        yield sse("done", {"finishReason": "stop"})

    return StreamingResponse(stream(), media_type="text/event-stream",
                             headers={"X-Accel-Buffering": "no"})


@app.post("/events/session-end")
async def session_end(request: Request):
    """I-20 수신 스텁 — 멱등: 없는 세션도 200 (05 §2-1)."""
    body = await request.json()
    print(f"[I-20] session-end: {body}")
    return {"cleared": True}


@app.get("/health")
async def health():
    return {"status": "ok"}
