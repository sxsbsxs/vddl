from fastapi import APIRouter, HTTPException
from redis.exceptions import RedisError
from sqlalchemy import text
from app.db import engine
from app.queue import get_redis_connection

router = APIRouter(tags=["system"])

@router.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}

@router.get("/ready")
def ready() -> dict[str, str]:
    try:
        with engine.connect() as connection:
            connection.execute(text("SELECT 1"))
        get_redis_connection().ping()
    except Exception as exc:
        raise HTTPException(status_code=503, detail="A required dependency is unavailable") from exc
    return {"status":"ready","database":"ok","redis":"ok"}
