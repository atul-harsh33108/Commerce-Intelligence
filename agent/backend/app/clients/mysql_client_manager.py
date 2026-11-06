import asyncio
import json
from datetime import date, datetime, time
from decimal import Decimal
from typing import Any, Optional

from sqlalchemy import text
from sqlalchemy.ext.asyncio import create_async_engine, AsyncEngine, async_sessionmaker

from app.conf.app_config import DBConfig, app_config


def _json_default(value: Any) -> str:
    """Make the types a real warehouse schema contains JSON-serialisable.

    WHY THIS IS NEEDED, and it is a latent bug rather than a new requirement.

    `build_meta_knowledge` samples example values out of the warehouse and stores them in
    `column_info.examples`, which is a JSON column. Python's json encoder handles str/int/float/bool
    and nothing else - so the moment a sampled column is a DATE, a DATETIME or a DECIMAL, the insert
    fails with:

        StatementError: (builtins.TypeError) Object of type date is not JSON serializable

    The original warehouse this agent was written against happened to have none of those types in
    the columns it sampled. This one does, necessarily: money is DECIMAL(12,2) because binary floats
    cannot represent 0.10, and `dim_date.full_date` is a DATE because that is what a date is.

    Decimal is stringified rather than cast to float ON PURPOSE. These values end up in an LLM
    prompt as examples of what a column looks like, and `27.34` is what the column looks like -
    `27.340000000000003` is not.
    """
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, (datetime, date, time)):
        return value.isoformat()
    if isinstance(value, (bytes, bytearray)):
        return value.decode("utf-8", errors="replace")
    return str(value)


class MysqlClientManager:
    def __init__(self, db_config: DBConfig):
        self.db_config = db_config
        self.engine: Optional[AsyncEngine] = None
        self.session_factory = None

    def _get_url(self):
        return f"mysql+asyncmy://{self.db_config.user}:{self.db_config.password}@{self.db_config.host}:{self.db_config.port}/{self.db_config.database}?charset=utf8mb4"

    def init(self):
        self.engine = create_async_engine(url=self._get_url(),
                                          pool_size=10,
                                          pool_pre_ping=True,
                                          # See _json_default: without this, indexing a warehouse
                                          # that contains DATE, DATETIME or DECIMAL columns fails
                                          # on the first one it samples.
                                          json_serializer=lambda o: json.dumps(
                                              o, default=_json_default, ensure_ascii=False))
        self.session_factory = async_sessionmaker(self.engine,
                                                  autoflush=True,
                                                  expire_on_commit=False,
                                                  autobegin=True)

    async def close(self):
        await self.engine.dispose()


dw_mysql_client_manager = MysqlClientManager(app_config.db_dw)
meta_mysql_client_manager = MysqlClientManager(app_config.db_meta)

if __name__ == '__main__':
    meta_mysql_client_manager.init()


    async def test():
        async with meta_mysql_client_manager.session_factory() as session:
            result = await session.execute(text("select * from table_info limit 10"))
            rows = result.fetchall()
            print(rows)


    asyncio.run(test())
