from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from app.api.routes.health import router as health_router
from app.api.routes.platforms import router as platforms_router
from app.api.routes.tasks import router as tasks_router
from app.core.config import settings

app = FastAPI(title=settings.app_name, version="0.2.0", description="API for user-owned and otherwise authorized media workflows.")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[settings.web_origin],
    allow_credentials=False,
    allow_methods=["GET", "POST"],
    allow_headers=["Content-Type", "Authorization", "X-API-Key"],
)
app.include_router(health_router, prefix="/api/v1")
app.include_router(platforms_router, prefix="/api/v1")
app.include_router(tasks_router, prefix="/api/v1")

@app.get("/", tags=["system"])
def root() -> dict[str, str]:
    return {"name": settings.app_name, "status":"scaffold", "docs":"/docs"}
