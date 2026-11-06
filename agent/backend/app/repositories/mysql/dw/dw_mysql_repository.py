from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.sql_safety import validate_readonly_sql


class DWMySQLRepository:
    def __init__(self, session: AsyncSession):
        self.session = session

    async def get_column_types(self, table_name: str) -> dict[str, str]:
        sql = f"show columns from {table_name}"
        result = await self.session.execute(text(sql))
        return {row.Field: row.Type for row in result.fetchall()}

    async def get_column_values(self, table_name: str, column_name: str, limit: int):
        sql = f"select distinct {column_name} from {table_name} limit {limit}"
        result = await self.session.execute(text(sql))
        return result.scalars().fetchall()

    async def get_db_info(self):
        result = await self.session.execute(text("select version()"))
        version = result.scalar()

        dialect = self.session.get_bind().dialect.name

        return {'version': version, 'dialect': dialect}

    async def validate_sql(self, sql):
        safe_sql = validate_readonly_sql(sql)
        await self.session.execute(text(f"explain {safe_sql}"))

    async def execute_sql(self, sql):
        safe_sql = validate_readonly_sql(sql)
        result = await self.session.execute(text(safe_sql))
        return [dict(row) for row in result.mappings().fetchall()]
