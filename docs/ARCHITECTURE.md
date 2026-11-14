# Architecture

## Initial architecture

The first release uses a modular monolith with independently deployable web, API, and worker processes.

- `apps/web`: Next.js user interface and admin console.
- `services/api`: FastAPI REST API, authentication, authorization, provider registry, and job orchestration.
- `services/worker`: Redis Queue workers for authorized file processing.
- PostgreSQL: durable users, API keys, jobs, provider capabilities, and audit records.
- Redis: queue transport, short-lived state, and rate-limit counters.
- FFmpeg/FFprobe: inspect and process files that the user owns or is authorized to download.

## Provider adapter contract

Each provider adapter must declare its capabilities and authorization requirements. A provider must not be marked downloadable until the integration uses a documented, permitted download mechanism. Metadata access and file download are separate capabilities.

## Security boundaries

- Allowlist provider domains and validate every redirect.
- Reject loopback, private, link-local, multicast, and cloud metadata IP ranges to mitigate SSRF.
- Never fetch arbitrary user-supplied URLs from privileged network contexts.
- Do not store provider passwords or session cookies.
- Do not bypass DRM, authentication, signed access controls, paywalls, or platform restrictions.
- Use short-lived signed object-storage URLs for completed files.
- Apply job quotas, size limits, timeouts, and cleanup policies.

## Evolution

Keep the API and worker in Python initially. Introduce Go or Rust only for a measured bottleneck with a stable internal API contract. FFmpeg is consumed as a pinned external tool; the project does not need to reimplement codecs in C/C++.
