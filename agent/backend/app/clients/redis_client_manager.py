import json
import hashlib
from typing import Optional

import redis.asyncio as aioredis

from app.conf.app_config import app_config

# Default TTLs in seconds
TTL_EMBEDDING = 86400       # 24 hours
TTL_SEARCH_RESULT = 14400   # 4 hours


class RedisClientManager:
    def __init__(self):
        self.client: Optional[aioredis.Redis] = None

    def init(self):
        cfg = app_config.redis
        self.client = aioredis.Redis(host=cfg.host, port=cfg.port, db=cfg.db, decode_responses=True)

    async def close(self):
        if self.client:
            await self.client.aclose()

    # ── Embedding cache ──

    @staticmethod
    def _embedding_key(text: str) -> str:
        return f"emb:{hashlib.md5(text.encode()).hexdigest()}"

    async def get_embedding(self, text: str) -> Optional[list[float]]:
        raw = await self.client.get(self._embedding_key(text))
        if raw:
            return json.loads(raw)
        return None

    async def set_embedding(self, text: str, embedding: list[float]):
        await self.client.set(self._embedding_key(text), json.dumps(embedding), ex=TTL_EMBEDDING)

    # ── Search result cache ──

    @staticmethod
    def _search_key(prefix: str, query: str) -> str:
        return f"{prefix}:{hashlib.md5(query.encode()).hexdigest()}"

    async def get_search_result(self, prefix: str, query: str) -> Optional[list]:
        raw = await self.client.get(self._search_key(prefix, query))
        if raw:
            return json.loads(raw)
        return None

    async def set_search_result(self, prefix: str, query: str, result: list):
        await self.client.set(self._search_key(prefix, query), json.dumps(result, default=str), ex=TTL_SEARCH_RESULT)


redis_client_manager = RedisClientManager()
