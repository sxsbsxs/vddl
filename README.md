# VideoFetch Pro

A modular, multilingual platform for managing and downloading media files that users own or are authorized to download.

## Project status

Early scaffold. The first milestone establishes the repository structure, API foundation, worker boundary, and deployment baseline. Platform integrations are enabled only where the relevant platform provides an authorized download path. This project does not bypass DRM, authentication, signatures, paywalls, or other access controls.

## Planned stack

- Web: Next.js, React, TypeScript, Tailwind CSS, next-intl
- API: Python, FastAPI, Pydantic, SQLAlchemy
- Queue: Redis + RQ
- Database: PostgreSQL
- Media tooling: FFmpeg / FFprobe for authorized files only
- Deployment: Docker Compose
- Later optimization: Go or Rust services only when profiling demonstrates a need

## Repository layout

```text
apps/web/             Next.js user interface and admin console
services/api/         FastAPI application
services/worker/      RQ background worker
packages/contracts/   Shared API schemas and contracts
infra/                Docker and deployment configuration
docs/                 Architecture and development documentation
```

## Development

The initial scaffold and local development instructions will be added incrementally. Copy `.env.example` to `.env` before starting the stack.

## Security and compliance

Only process media files supplied by the user or obtained through documented, authorized platform APIs and download permissions. Do not use this service to circumvent DRM, authentication, access controls, or platform restrictions. Validate remote URLs and block private, loopback, link-local, and metadata-service destinations to mitigate SSRF.

## License

License selection is pending dependency and distribution review. Do not assume this repository grants rights to third-party platform content.
