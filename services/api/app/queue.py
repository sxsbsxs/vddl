from redis import Redis
from rq import Queue
from app.core.config import settings

def get_redis_connection() -> Redis:
    return Redis.from_url(settings.redis_url, socket_connect_timeout=3, socket_timeout=5)

def get_task_queue() -> Queue:
    return Queue(settings.rq_queue_name, connection=get_redis_connection(), default_timeout=3600)
