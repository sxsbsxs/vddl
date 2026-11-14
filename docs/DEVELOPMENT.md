# Local development

## Prerequisites
- Docker Engine and Docker Compose plugin
- Git

## Start
1. Clone this repository.
2. Copy .env.example to .env.
3. Set API_ACCESS_TOKEN to a long random value; replace the sample database password before exposing any service to a network.
4. Run `docker compose up --build`.
5. Open the web app at http://localhost:3000 and API docs at http://localhost:8000/docs.
6. Check dependencies at http://localhost:8000/api/v1/ready.

The API applies Alembic migrations before starting. The Redis/RQ worker is a separate container. The platform registry is intentionally empty until a reviewed, authorized provider adapter is added. Therefore, POST /api/v1/tasks returns 422 for all URLs in the initial scaffold; this is a deliberate fail-closed state.

## API calls
Set the same API_ACCESS_TOKEN in the request header:

```bash
curl -H "X-API-Key: $API_ACCESS_TOKEN" http://localhost:8000/api/v1/tasks
```

## Tests
From services/api:

```bash
python -m pip install -e '.[dev]'
pytest
```

## Safety
Keep .env out of Git. Do not expose PostgreSQL or Redis publicly. Place the application behind HTTPS and a reverse proxy in production. The bootstrap token is not a substitute for production user accounts or per-user authorization.
