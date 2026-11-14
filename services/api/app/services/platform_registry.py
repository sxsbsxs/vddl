from typing import TypedDict


class PlatformCapability(TypedDict):
    id: str
    name: str
    metadata: bool
    authorized_download: bool
    mp4_output: bool
    status: str


# No platform is advertised as downloadable until an official, authorized
# integration has been implemented and verified.
_PLATFORMS: list[PlatformCapability] = []


def list_platform_capabilities() -> list[PlatformCapability]:
    return list(_PLATFORMS)
