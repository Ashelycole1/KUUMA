"""Database base: one PostgreSQL schema per build, migrated by that build's own Alembic history.

Rule 2 (own your data): each service connects as its own role (ai_svc, companion_svc,
casefile_svc, guardian_svc), which infra/postgres/init.sql grants rights on its schema
only. A service therefore cannot read another build's tables even by mistake.

    # modules/guardian/service/kuuma_guardian_service/db.py
    Base = make_base("guardian")

    # modules/guardian/service/migrations/env.py
    from kuuma_server_common.db import run_alembic_env
    run_alembic_env("guardian", Base.metadata)
"""

from __future__ import annotations

import os

from sqlalchemy import MetaData, create_engine, text
from sqlalchemy.engine import Engine
from sqlalchemy.orm import DeclarativeBase

BUILD_SCHEMAS = ("ai", "companion", "casefile", "guardian")


def _check_schema(schema: str) -> None:
    if schema not in BUILD_SCHEMAS:
        raise ValueError(f"unknown schema {schema!r}; builds own exactly {BUILD_SCHEMAS}")


def make_base(schema: str) -> type[DeclarativeBase]:
    """A declarative base whose tables all live in `schema`."""
    _check_schema(schema)

    class Base(DeclarativeBase):
        metadata = MetaData(schema=schema)

    Base.__name__ = f"{schema.capitalize()}Base"
    return Base


def database_url(schema: str) -> str:
    """DATABASE_URL_<SCHEMA> (preferred) or DATABASE_URL, e.g. postgresql+psycopg://guardian_svc:...@db/kuuma."""
    _check_schema(schema)
    url = os.environ.get(f"DATABASE_URL_{schema.upper()}") or os.environ.get("DATABASE_URL")
    if not url:
        raise RuntimeError(f"set DATABASE_URL_{schema.upper()} (see infra/docker-compose.yml)")
    return url


def make_engine(schema: str, url: str | None = None) -> Engine:
    """Engine whose connections default to the build's schema (search_path)."""
    _check_schema(schema)
    return create_engine(
        url or database_url(schema),
        pool_pre_ping=True,
        connect_args={"options": f"-csearch_path={schema}"},
    )


def run_alembic_env(schema: str, target_metadata: MetaData) -> None:
    """Body of a build's migrations/env.py. Keeps the version table inside the build's schema."""
    from alembic import context

    _check_schema(schema)
    config = context.config
    url = config.get_main_option("sqlalchemy.url") or database_url(schema)

    if context.is_offline_mode():
        context.configure(
            url=url,
            target_metadata=target_metadata,
            version_table_schema=schema,
            include_schemas=False,
            literal_binds=True,
        )
        with context.begin_transaction():
            context.run_migrations()
        return

    engine = make_engine(schema, url)
    with engine.connect() as conn:
        conn.execute(text(f'CREATE SCHEMA IF NOT EXISTS "{schema}"'))
        conn.commit()
        context.configure(
            connection=conn,
            target_metadata=target_metadata,
            version_table_schema=schema,
            include_schemas=False,
        )
        with context.begin_transaction():
            context.run_migrations()
