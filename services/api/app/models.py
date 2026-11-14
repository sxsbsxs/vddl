from __future__ import annotations
import uuid
from datetime import datetime
from sqlalchemy import DateTime, Index, Integer, JSON, String, Text, Uuid, func
from sqlalchemy.orm import Mapped, mapped_column
from app.db import Base

class DownloadTask(Base):
    __tablename__ = "download_tasks"
    __table_args__ = (
        Index("ix_download_tasks_status_created_at", "status", "created_at"),
        Index("ix_download_tasks_platform_id", "platform_id"),
    )
    id: Mapped[uuid.UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True, default=uuid.uuid4)
    platform_id: Mapped[str] = mapped_column(String(80), nullable=False)
    source_url: Mapped[str] = mapped_column(Text, nullable=False)
    status: Mapped[str] = mapped_column(String(24), nullable=False, default="queued", server_default="queued")
    progress: Mapped[int] = mapped_column(Integer, nullable=False, default=0, server_default="0")
    error_code: Mapped[str | None] = mapped_column(String(80), nullable=True)
    error_message: Mapped[str | None] = mapped_column(String(500), nullable=True)
    metadata_json: Mapped[dict] = mapped_column("metadata", JSON, nullable=False, default=dict, server_default="{}")
    output_key: Mapped[str | None] = mapped_column(String(1024), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now())
