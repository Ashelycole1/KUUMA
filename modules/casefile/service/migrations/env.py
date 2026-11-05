from kuuma_casefile_service.db import Base

from kuuma_server_common.db import run_alembic_env

run_alembic_env("casefile", Base.metadata)
