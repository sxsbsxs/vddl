from app.services.platform_registry import list_platform_capabilities, resolve_authorized_adapter

def test_no_provider_is_advertised_until_verified() -> None:
    assert list_platform_capabilities() == []
    assert resolve_authorized_adapter("https://example.com/video") is None
