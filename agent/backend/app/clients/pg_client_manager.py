from typing import Optional

from sqlalchemy.ext.asyncio import create_async_engine, AsyncEngine, async_sessionmaker

from app.conf.app_config import app_config


class PGClientManager:
    def __init__(self):
        self.engine: Optional[AsyncEngine] = None
        self.session_factory = None

    def init(self):
        cfg = app_config.db_logs
        url = f"postgresql+asyncpg://{cfg.user}:{cfg.password}@{cfg.host}:{cfg.port}/{cfg.database}"
        self.engine = create_async_engine(url, pool_size=5, pool_pre_ping=True)
        self.session_factory = async_sessionmaker(self.engine, autoflush=True, expire_on_commit=False)

    async def close(self):
        if self.engine:
            await self.engine.dispose()


pg_client_manager = PGClientManager()
