import hashlib
from importlib.resources import files

from psycopg import AsyncConnection
from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from jsd_aird_agents.errors import AgentError
from jsd_aird_agents.settings import Settings


class Database:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.pool = AsyncConnectionPool(
            settings.database_url.get_secret_value(),
            min_size=1,
            max_size=16,
            open=False,
            kwargs={"autocommit": True, "row_factory": dict_row},
        )
        self.checkpoint_pool = AsyncConnectionPool(
            settings.database_url.get_secret_value(),
            min_size=1,
            max_size=8,
            open=False,
            kwargs={
                "autocommit": True,
                "row_factory": dict_row,
                "options": "-c search_path=agent_checkpoint,public",
            },
        )
        self.checkpointer = None

    async def open(self):
        await self.pool.open(wait=True, timeout=10)

    async def enable_checkpoints(self):
        if self.checkpointer is None:
            from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

            await self.checkpoint_pool.open(wait=True, timeout=10)
            self.checkpointer = AsyncPostgresSaver(self.checkpoint_pool)

    async def close(self):
        await self.checkpoint_pool.close()
        await self.pool.close()

    async def migrate(self):
        from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver

        async with self.pool.connection() as conn:
            async with conn.transaction():
                await conn.execute("SELECT pg_advisory_xact_lock(1847091001)")
                await conn.execute("CREATE SCHEMA IF NOT EXISTS agent_runtime")
                await conn.execute("""CREATE TABLE IF NOT EXISTS agent_runtime.schema_version
                    (version integer PRIMARY KEY, sha256 text NOT NULL, installed_at timestamptz NOT NULL DEFAULT now())""")
                applied = {
                    r["version"]: r["sha256"]
                    for r in await (
                        await conn.execute("SELECT version,sha256 FROM agent_runtime.schema_version")
                    ).fetchall()
                }
                migrations = sorted(
                    files("jsd_aird_agents.persistence").joinpath("migrations").iterdir(),
                    key=lambda p: p.name,
                )
                available = {int(p.name.split("_", 1)[0]) for p in migrations if p.name.endswith(".sql")}
                if set(applied) - available:
                    raise AgentError("MIGRATION_VERSION_MISMATCH", "状态库比当前代码的迁移版本新", 500)
                for migration in migrations:
                    if not migration.name.endswith(".sql"):
                        continue
                    version = int(migration.name.split("_", 1)[0])
                    source = migration.read_text(encoding="utf-8")
                    checksum = hashlib.sha256(source.encode()).hexdigest()
                    if version in applied:
                        if applied[version] != checksum:
                            raise AgentError("MIGRATION_CHECKSUM_MISMATCH", "已执行迁移内容发生变化", 500)
                        continue
                    await conn.execute(source)
                    await conn.execute(
                        "INSERT INTO agent_runtime.schema_version(version,sha256) VALUES(%s,%s)",
                        (version, checksum),
                    )
        async with await AsyncConnection.connect(
            self.settings.database_url.get_secret_value(),
            autocommit=True,
            row_factory=dict_row,
            options="-c search_path=agent_checkpoint,public",
        ) as conn:
            await AsyncPostgresSaver(conn).setup()

    async def one(self, sql, params=()):
        async with self.pool.connection() as conn:
            return await (await conn.execute(sql, params)).fetchone()

    async def all(self, sql, params=()):
        async with self.pool.connection() as conn:
            return await (await conn.execute(sql, params)).fetchall()

    async def execute(self, sql, params=()):
        async with self.pool.connection() as conn:
            return (await conn.execute(sql, params)).rowcount
