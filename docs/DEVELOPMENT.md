# Local development

## Prerequisites
- Docker Engine and Docker Compose plugin
- Git

## Start the stack
1. Clone this repository.
2. Copy .env.example to .env and replace development secrets before exposing the stack to a network.
3. Run docker compose up --build.
4. Open the web app at http://localhost:3000.
5. Open API documentation at http://localhost:8000/docs.

The current web form is intentionally non-submitting and the platform registry is empty. The first scaffold establishes the web and API containers; authorized provider integrations and persistent job processing are later milestones.

## API tests
From services/api, install the development extras and run pytest:
python -m pip install -e '.[dev]'
pytest

## Environment safety
Do not use default development credentials outside a local machine. Keep .env out of Git. Do not expose PostgreSQL or Redis ports publicly. Place the application behind HTTPS and a reverse proxy for production.
