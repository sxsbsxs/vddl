import hmac
from fastapi import Header, HTTPException
from app.core.config import settings

def require_api_key(x_api_key: str | None = Header(default=None, alias="X-API-Key")) -> None:
    expected = settings.api_access_token
    if not expected:
        raise HTTPException(status_code=503, detail="API access token is not configured")
    if not x_api_key or not hmac.compare_digest(x_api_key, expected):
        raise HTTPException(status_code=401, detail="Invalid or missing API key")
