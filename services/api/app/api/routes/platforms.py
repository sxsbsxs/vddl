from fastapi import APIRouter

from app.services.platform_registry import list_platform_capabilities

router = APIRouter(prefix="/platforms", tags=["platforms"])


@router.get("")
async def get_platforms() -> dict[str, list[dict[str, object]]]:
    """Return only integrations explicitly registered as available."""
    return {"items": list_platform_capabilities()}
