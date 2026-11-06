from dataclasses import dataclass
import os
from pathlib import Path

from omegaconf import OmegaConf

from app.conf.config_loader import load_config


# Logging configuration
@dataclass
class File:
    enable: bool
    level: str
    path: str
    rotation: str
    retention: str


@dataclass
class Console:
    enable: bool
    level: str


@dataclass
class LoggingConfig:
    file: File
    console: Console


# Database configuration
@dataclass
class DBConfig:
    host: str
    port: int
    user: str
    password: str
    database: str


@dataclass
class QdrantConfig:
    host: str
    port: int
    embedding_size: int


@dataclass
class EmbeddingConfig:
    host: str
    port: int
    model: str


@dataclass
class ESConfig:
    host: str
    port: int
    index_name: str


@dataclass
class RedisConfig:
    host: str
    port: int
    db: int = 0


@dataclass
class PGConfig:
    host: str
    port: int
    user: str
    password: str
    database: str


@dataclass
class LLMConfig:
    model_name: str
    api_key: str
    provider: str = "openai"
    base_url: str = ""


@dataclass
class AppConfig:
    logging: LoggingConfig
    db_meta: DBConfig
    db_dw: DBConfig
    qdrant: QdrantConfig
    embedding: EmbeddingConfig
    es: ESConfig
    redis: RedisConfig
    db_logs: PGConfig
    llm: LLMConfig


config_file = Path(os.getenv("APP_CONFIG_FILE", Path(__file__).parents[2] / 'conf' / 'app_config.yaml'))
context = OmegaConf.load(config_file)
schema = OmegaConf.structured(AppConfig)
app_config: AppConfig = OmegaConf.to_object(OmegaConf.merge(schema, context))

if __name__ == '__main__':
    print(app_config.es.host)
