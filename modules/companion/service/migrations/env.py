from kuuma_companion_service.db import Base

from kuuma_server_common.db import run_alembic_env

run_alembic_env("companion", Base.metadata)
