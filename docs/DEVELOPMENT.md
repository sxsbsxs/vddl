# Local development

## Backend and web console
1. Clone the repository and copy .env.example to .env.
2. Set API_ACCESS_TOKEN to a long random value; replace the sample database password before exposing any service to a network.
3. Run `docker compose up --build`.
4. Open the API docs at http://localhost:8000/docs and readiness endpoint at http://localhost:8000/api/v1/ready.
5. The Next.js application under apps/web is reserved for the administrator console; it is not the Android end-user app.

The API applies Alembic migrations before starting. Redis/RQ runs in a separate worker container. The provider registry is intentionally empty, so POST /api/v1/tasks returns 422 until a reviewed adapter explicitly allows authorized downloads.

## Android application
The primary user client lives under [apps/android](../apps/android). It is a native Kotlin + Jetpack Compose project with Chinese, English, and Japanese resources.

Open apps/android in Android Studio and sync Gradle. It targets Android API 37 and supports Android 8.0 (API 26) and newer. See [the Android guide](../apps/android/README.md) for requirements and build commands.

The debug API base URL uses http://10.0.2.2:8000/ for the Android emulator. For a physical phone, configure a reachable development host or HTTPS endpoint. Release builds require HTTPS.

## Backend tests
From services/api:

```bash
python -m pip install -e '.[dev]'
pytest
```

## Safety
Keep .env out of Git. Do not expose PostgreSQL or Redis publicly. Deploy behind HTTPS and a reverse proxy. The bootstrap token is not a substitute for user accounts or per-user authorization; it must not be embedded in the Android APK.
