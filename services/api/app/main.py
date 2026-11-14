from fastapi import FastAPI

from app.api.routes.health import router as health_router
from app.api.routes.platforms import router as platforms_router
from app.core.config import settings

app = FastAPI(
    title=settings.app_name,
    version="0.1.0",
    description="API for user-owned and otherwise authorized media workflows.",
)

app.include_router(health_router, prefix="/api/v1")
app.include_router(platforms_router, prefix="/api/v1")


@app.get("/", tags=["system"])
async def root() -> dict[str, str]:
    return {"name": settings.app_name, "status": "scaffold", "docs": "/docs"}
