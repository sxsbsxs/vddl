from datetime import datetime
from enum import StrEnum
from uuid import UUID
from pydantic import BaseModel, ConfigDict, Field

class TaskStatus(StrEnum):
    QUEUED = "queued"
    PROCESSING = "processing"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"

class TaskCreateRequest(BaseModel):
    source_url: str = Field(min_length=10, max_length=4096)

class TaskResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)
    id: UUID
    platform_id: str
    status: TaskStatus
    progress: int
    error_code: str | None
    error_message: str | None
    metadata_json: dict = Field(serialization_alias="metadata")
    output_key: str | None
    created_at: datetime
    updated_at: datetime
