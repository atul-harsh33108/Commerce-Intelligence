import asyncio
from argparse import ArgumentParser
from pathlib import Path

from app.clients.embedding_client_manager import embedding_client_manager
from app.clients.es_client_manager import es_client_manager
from app.clients.mysql_client_manager import (
    meta_mysql_client_manager,
    dw_mysql_client_manager,
)
from app.clients.qdrant_client_manager import qdrant_client_manager
from app.repositories.qdrant.column_qdrant_repository import ColumnQdrantRepository
from app.repositories.mysql.dw.dw_mysql_repository import DWMySQLRepository
from app.repositories.mysql.meta.meta_mysql_repository import MetaMySQLRepository
from app.repositories.es.value_es_repository import ValueESRepository
from app.repositories.qdrant.metric_qdrant_repository import MetricQdrantRepository
from app.services.meta_knowledge_service import MetaKnowledgeService


async def build(config_path: Path):
    meta_mysql_client_manager.init()  # Initialize metadata MySQL client
    dw_mysql_client_manager.init()  # Initialize data warehouse MySQL client
    qdrant_client_manager.init()  # Initialize Qdrant client
    embedding_client_manager.init()  # Initialize Embedding client
    es_client_manager.init()  # Initialize Elasticsearch client

    async with (
        meta_mysql_client_manager.session_factory() as meta_session,
        dw_mysql_client_manager.session_factory() as dw_session,
    ):
        meta_mysql_repository = MetaMySQLRepository(
            meta_session
        )  # Create metadata MySQL repository instance
        dw_mysql_repository = DWMySQLRepository(dw_session)  # Create data warehouse MySQL repository instance
        column_qdrant_repository = ColumnQdrantRepository(
            qdrant_client_manager.client
        )  # Create column Qdrant repository instance
        embedding_client = embedding_client_manager.client  # Get Embedding client instance
        value_es_repository = ValueESRepository(
            es_client_manager.client
        )  # Create value Elasticsearch repository instance
        metric_qdrant_repository = MetricQdrantRepository(
            qdrant_client_manager.client
        )  # Create metric Qdrant repository instance

        mete_knowledge_service = MetaKnowledgeService(
            meta_mysql_repository=meta_mysql_repository,
            dw_mysql_repository=dw_mysql_repository,
            column_qdrant_repository=column_qdrant_repository,
            embedding_client=embedding_client,
            value_es_repository=value_es_repository,
            metric_qdrant_repository=metric_qdrant_repository,
        )  # Create MetaKnowledgeService instance
        await mete_knowledge_service.build(config_path)  # Build meta knowledge base

    await meta_mysql_client_manager.close()  # Close metadata MySQL client
    await dw_mysql_client_manager.close()  # Close data warehouse MySQL client
    await qdrant_client_manager.close()  # Close Qdrant client
    await es_client_manager.close()  # Close Elasticsearch client


if __name__ == "__main__":
    parser = ArgumentParser()

    parser.add_argument("-c", "--conf")  # option that takes a value

    args = parser.parse_args()

    config_path = Path(args.conf)

    asyncio.run(build(config_path))
