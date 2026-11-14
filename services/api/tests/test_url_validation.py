import pytest
from app.security.url_validation import UnsafeSourceUrl, validate_source_url

def test_accepts_https_public_hostname() -> None:
    assert validate_source_url("https://media.example.com/watch?id=123") == "https://media.example.com/watch?id=123"

@pytest.mark.parametrize("url", [
    "http://media.example.com/video",
    "file:///etc/passwd",
    "ftp://media.example.com/video",
    "https://127.0.0.1/video",
    "https://localhost/video",
    "https://printer.local/video",
    "https://user:pass@example.com/video",
    "https://example.com:8443/video",
    "https://example.com/video#fragment",
])
def test_rejects_unsafe_source_urls(url: str) -> None:
    with pytest.raises(UnsafeSourceUrl):
        validate_source_url(url)
