# Security baseline

This service is intended for media the operator or user owns, or has explicit permission to download.

## Implemented in this scaffold
- HTTPS-only URL syntax checks; reject credentials, non-standard ports, IP-literal hosts, local names, and fragments.
- Task endpoints require the configured bootstrap API token in the X-API-Key header. Configure a long random API_ACCESS_TOKEN before using them.
- No provider adapter is enabled by default. Task creation fails closed unless an adapter explicitly declares authorized-download support.
- Workers receive task identifiers, not shell commands or caller-controlled command fragments.
- Client-visible errors avoid echoing source URLs, tokens, and provider credentials.
- CORS is restricted to the configured web origin.

## Required before production
- Replace the single bootstrap token with user authentication, per-user task ownership, hashed individual API keys, RBAC, quotas, and audit logs.
- Enforce provider host allow-lists and resolve DNS immediately before connecting; reject private, loopback, link-local, multicast, and cloud metadata addresses.
- Revalidate every redirect and defend against DNS rebinding; restrict worker egress at the network layer.
- Enforce file size and duration limits, worker isolation, rate limits, and automatic cleanup.
- Use private object storage and short-lived signed download URLs.
- Deploy behind HTTPS; never expose PostgreSQL or Redis publicly.
- Review platform terms, content permissions, and all dependency licenses.

## Unsupported
Do not bypass DRM, authentication, signed access controls, paywalls, private content permissions, or platform restrictions. A public share URL is not proof of download permission.
