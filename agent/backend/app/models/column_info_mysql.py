from sqlalchemy import String, Text
from sqlalchemy.types import JSON
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base


class ColumnInfoMySQL(Base):
    __tablename__ = "column_info"

    id: Mapped[str] = mapped_column(
        String(64),
        primary_key=True,
        comment="Column ID"
    )
    name: Mapped[str | None] = mapped_column(
        String(128),
        comment="Column name"
    )
    type: Mapped[str | None] = mapped_column(
        String(64),
        comment="Data type"
    )
    role: Mapped[str | None] = mapped_column(
        String(32),
        comment="Column role (primary_key,foreign_key,measure,dimension)"
    )
    examples: Mapped[dict | list | None] = mapped_column(
        JSON,
        comment="Data examples"
    )
    description: Mapped[str | None] = mapped_column(
        Text,
        comment="Column description"
    )
    alias: Mapped[dict | list | None] = mapped_column(
        JSON,
        comment="Column aliases"
    )
    table_id: Mapped[str | None] = mapped_column(
        String(64),
        comment="Parent table ID"
    )
