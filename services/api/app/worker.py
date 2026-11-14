import logging
import tempfile
from pathlib import Path
from uuid import UUID
from app.db import SessionLocal
from app.models import DownloadTask
from app.services.platform_registry import resolve_authorized_adapter

logger = logging.getLogger(__name__)

def process_download_task(task_id: str) -> None:
    task_uuid = UUID(task_id)
    with SessionLocal() as db:
        task = db.get(DownloadTask, task_uuid)
        if task is None:
            logger.warning("RQ job references missing task id=%s", task_id)
            return
        task.status = "processing"
        task.progress = 1
        task.error_code = None
        task.error_message = None
        db.commit()
        source_url = task.source_url
    try:
        adapter = resolve_authorized_adapter(source_url)
        if adapter is None or not adapter.supports_authorized_download:
            raise RuntimeError("authorized_provider_unavailable")
        with tempfile.TemporaryDirectory(prefix="videofetch-") as temp_dir:
            output_key = adapter.process_authorized_file(source_url, task_id, Path(temp_dir))
        with SessionLocal() as db:
            task = db.get(DownloadTask, task_uuid)
            if task is not None:
                task.status = "completed"
                task.progress = 100
                task.output_key = output_key
                db.commit()
    except Exception as exc:
        code = str(exc) if str(exc) == "authorized_provider_unavailable" else "processing_failed"
        logger.exception("Media task failed id=%s code=%s", task_id, code)
        with SessionLocal() as db:
            task = db.get(DownloadTask, task_uuid)
            if task is not None:
                task.status = "failed"
                task.error_code = code
                task.error_message = "The media processing job could not be completed"
                db.commit()
