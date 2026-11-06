# One image for every Nilinde Python service; docker-compose picks the app with APP / SERVICE_DIR.
# Build context: the repo root.
FROM python:3.12.10-slim

COPY --from=ghcr.io/astral-sh/uv:0.13.7 /uv /usr/local/bin/uv

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PROJECT_ENVIRONMENT=/opt/venv \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    KUUMA_ENV=local

WORKDIR /app

# The whole uv workspace must be present for a frozen sync.
COPY pyproject.toml uv.lock ./
COPY packages/contracts/generated/python packages/contracts/generated/python
COPY packages/contracts/openapi packages/contracts/openapi
COPY packages/lang/python packages/lang/python
COPY packages/server-common packages/server-common
COPY packages/kuuma-llm packages/kuuma-llm
COPY fakes/python fakes/python
COPY fakes/scenarios fakes/scenarios
COPY ai/service ai/service
COPY modules/companion/service modules/companion/service
COPY modules/casefile/service modules/casefile/service
COPY modules/guardian/service modules/guardian/service
COPY infra/seed infra/seed

RUN uv sync --frozen --no-dev --all-packages

ENV PATH=/opt/venv/bin:$PATH
RUN useradd --system --uid 10001 kuuma
USER kuuma

EXPOSE 8000
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8000/healthz').status==200 else 1)"

# Migrate this build's schema, then serve.
CMD ["sh", "-c", "alembic -c ${SERVICE_DIR}/alembic.ini upgrade head && exec uvicorn ${APP} --host 0.0.0.0 --port 8000 --no-access-log"]
