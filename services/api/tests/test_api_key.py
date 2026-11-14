import pytest
from fastapi import HTTPException
from app.security.api_key import require_api_key

def test_api_key_guard_rejects_missing_key_when_not_configured(monkeypatch) -> None:
    from app.security import api_key
    monkeypatch.setattr(api_key.settings, "api_access_token", "")
    with pytest.raises(HTTPException) as error:
        require_api_key("anything")
    assert error.value.status_code == 503

def test_api_key_guard_rejects_invalid_key(monkeypatch) -> None:
    from app.security import api_key
    monkeypatch.setattr(api_key.settings, "api_access_token", "correct-secret")
    with pytest.raises(HTTPException) as error:
        require_api_key("wrong-secret")
    assert error.value.status_code == 401

def test_api_key_guard_accepts_valid_key(monkeypatch) -> None:
    from app.security import api_key
    monkeypatch.setattr(api_key.settings, "api_access_token", "correct-secret")
    assert require_api_key("correct-secret") is None
