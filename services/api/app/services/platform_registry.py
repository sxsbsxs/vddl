from app.providers.base import PlatformAdapter

_ADAPTERS: list[PlatformAdapter] = []

def register_adapter(adapter: PlatformAdapter) -> None:
    if any(item.platform_id == adapter.platform_id for item in _ADAPTERS):
        raise ValueError(f"Adapter already registered: {adapter.platform_id}")
    _ADAPTERS.append(adapter)

def resolve_authorized_adapter(url: str) -> PlatformAdapter | None:
    for adapter in _ADAPTERS:
        if adapter.supports_authorized_download and adapter.matches(url):
            return adapter
    return None

def list_platform_capabilities() -> list[dict[str, object]]:
    return [{
        "id": item.platform_id,
        "name": item.display_name,
        "metadata": True,
        "authorized_download": item.supports_authorized_download,
        "mp4_output": False,
        "status": "registered",
    } for item in _ADAPTERS]
