from pathlib import Path
from typing import Protocol

class PlatformAdapter(Protocol):
    platform_id: str
    display_name: str
    supports_authorized_download: bool
    def matches(self, url: str) -> bool: ...
    def process_authorized_file(self, source_url: str, task_id: str, work_dir: Path) -> str:
        """Return a storage object key after permitted retrieval and validation."""
        ...
