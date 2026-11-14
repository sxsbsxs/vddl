from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    app_name: str = "VideoFetch Pro"
    app_env: str = "development"
    web_origin: str = "http://localhost:3000"
    database_url: str = "postgresql+psycopg://videofetch:videofetch_dev@localhost:5432/videofetch"
    redis_url: str = "redis://localhost:6379/0"
    rq_queue_name: str = "media-downloads"
    api_access_token: str = ""
    task_event_poll_seconds: int = 1
    task_event_max_seconds: int = 300
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

settings = Settings()
