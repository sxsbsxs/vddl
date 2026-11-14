# VideoFetch Pro

A modular, multilingual platform for managing media files that users own or are explicitly authorized to download.

## Current milestone
The repository contains a Next.js multilingual web shell, FastAPI REST API, PostgreSQL task persistence, Alembic migrations, Redis/RQ worker, task status/SSE endpoints, and a Docker Compose development stack.

**Provider integrations are not enabled.** The provider registry starts empty and task creation is rejected until a reviewed adapter explicitly supports authorized downloads. The project does not bypass DRM, authentication, signatures, paywalls, or other access controls.

## Stack
- Web: Next.js, React, TypeScript, next-intl
- API: Python, FastAPI, Pydantic, SQLAlchemy, Alembic
- Database: PostgreSQL
- Queue: Redis + RQ
- Media: FFmpeg / FFprobe planned for authorized files
- Deployment: Docker Compose

## Quick start
See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Set API_ACCESS_TOKEN before calling task endpoints.

## Security
Read [docs/SECURITY.md](docs/SECURITY.md). User accounts, per-user task ownership, DNS-rebinding-resistant outbound access, object storage, and production quotas remain required work.
