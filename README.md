# VideoFetch Pro

VideoFetch Pro is an Android-first media workflow product for content users own or are explicitly authorized to download.

## Product architecture
- **Android client (primary user app):** native Kotlin + Jetpack Compose in [apps/android](apps/android).
- **Admin web console:** Next.js + TypeScript in [apps/web](apps/web), reserved for operator and administrator workflows.
- **Backend API:** Python + FastAPI in [services/api](services/api).
- **Persistence and jobs:** PostgreSQL, SQLAlchemy, Alembic, Redis, and RQ.
- **Deployment:** Docker Compose.

## Android app
The Android app supports Chinese, English, and Japanese UI resources, clipboard paste, Android share-sheet link intake, preferred video quality, an AES-GCM encrypted local Cookie vault backed by Android Keystore, and a device-local download history. Cookie credentials are never sent to the backend. On-device provider adapters must be reviewed and must restrict Cookie use to explicitly allow-listed HTTPS hosts.

See [Android build instructions](apps/android/README.md).

## Backend milestone
PostgreSQL task persistence, initial migration, Redis/RQ worker process, task status and SSE endpoints, and basic URL validation are scaffolded.

**Provider integrations are not enabled.** The registry starts empty and rejects task creation until a reviewed adapter explicitly supports authorized downloads. Public share links do not automatically grant download permission. Local Cookie storage does not grant additional platform entitlements: it can only authenticate requests already permitted for the user's account. No DRM, paywall, or access-control bypass is implemented.

## Security
Read [docs/SECURITY.md](docs/SECURITY.md). Do not bypass DRM, authentication, signatures, paywalls, or platform access controls. Do not embed the shared backend bootstrap token in the Android app.
