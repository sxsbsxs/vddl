import json
import time
from uuid import UUID
from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.responses import StreamingResponse
from redis.exceptions import RedisError
from rq import Queue
from sqlalchemy import desc, select
from sqlalchemy.orm import Session
from app.core.config import settings
from app.db import get_db, SessionLocal
from app.models import DownloadTask
from app.queue import get_task_queue
from app.schemas.tasks import TaskCreateRequest, TaskResponse, TaskStatus
from app.security.url_validation import UnsafeSourceUrl, validate_source_url
from app.services.platform_registry import resolve_authorized_adapter

router = APIRouter(prefix="/tasks", tags=["tasks"])
_TERMINAL = {TaskStatus.COMPLETED.value, TaskStatus.FAILED.value, TaskStatus.CANCELLED.value}

def _get_task_or_404(db: Session, task_id: UUID) -> DownloadTask:
    task = db.get(DownloadTask, task_id)
    if task is None:
        raise HTTPException(status_code=404, detail="Task not found")
    return task

@router.post("", response_model=TaskResponse, status_code=202)
def create_task(payload: TaskCreateRequest, db: Session = Depends(get_db)) -> DownloadTask:
    try:
        source_url = validate_source_url(payload.source_url)
    except UnsafeSourceUrl as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    adapter = resolve_authorized_adapter(source_url)
    if adapter is None:
        raise HTTPException(status_code=422, detail="No verified provider adapter grants authorized downloads for this URL")
    task = DownloadTask(platform_id=adapter.platform_id, source_url=source_url, status="queued")
    db.add(task)
    db.commit()
    db.refresh(task)
    try:
        queue: Queue = get_task_queue()
        queue.enqueue("app.worker.process_download_task", str(task.id), job_timeout=3600)
    except (RedisError, OSError, RuntimeError) as exc:
        task.status = TaskStatus.FAILED.value
        task.error_code = "queue_unavailable"
        task.error_message = "The task queue is temporarily unavailable"
        db.commit()
        raise HTTPException(status_code=503, detail="Task queue is temporarily unavailable") from exc
    return task

@router.get("", response_model=list[TaskResponse])
def list_tasks(limit: int = Query(default=20, ge=1, le=100), db: Session = Depends(get_db)):
    statement = select(DownloadTask).order_by(desc(DownloadTask.created_at)).limit(limit)
    return list(db.scalars(statement).all())

@router.get("/{task_id}", response_model=TaskResponse)
def get_task(task_id: UUID, db: Session = Depends(get_db)) -> DownloadTask:
    return _get_task_or_404(db, task_id)

@router.get("/{task_id}/events")
def task_events(task_id: UUID):
    with SessionLocal() as db:
        _get_task_or_404(db, task_id)
    def stream():
        deadline = time.monotonic() + settings.task_event_max_seconds
        while time.monotonic() < deadline:
            with SessionLocal() as db:
                task = db.get(DownloadTask, task_id)
                if task is None:
                    yield 'event: error\ndata: {"message":"Task not found"}\n\n'
                    return
                payload = TaskResponse.model_validate(task).model_dump(mode="json")
                yield "event: task\ndata: " + json.dumps(payload, ensure_ascii=False) + "\n\n"
                if task.status in _TERMINAL:
                    return
            time.sleep(max(1, settings.task_event_poll_seconds))
        yield 'event: timeout\ndata: {"message":"Poll the task endpoint for current status"}\n\n'
    return StreamingResponse(stream(), media_type="text/event-stream", headers={"Cache-Control":"no-cache","X-Accel-Buffering":"no"})
