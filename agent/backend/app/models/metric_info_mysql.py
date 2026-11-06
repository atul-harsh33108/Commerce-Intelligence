from sqlalchemy import String, Text
from sqlalchemy.types import JSON
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class MetricInfoMySQL(Base):
    __tablename__ = "metric_info"

    id: Mapped[str] = mapped_column(
        String(64),
        primary_key=True,
        comment="Metric ID"
    )
    name: Mapped[str | None] = mapped_column(
        String(128),
        comment="Metric name"
    )
    description: Mapped[str | None] = mapped_column(
        Text,
        comment="Metric description"
    )
    relevant_columns: Mapped[dict | list | None] = mapped_column(
        JSON,
        comment="Related columns"
    )
    alias: Mapped[dict | list | None] = mapped_column(
        JSON,
        comment="Metric aliases"
    )
