from typing import TypedDict

from app.entities.column_info import ColumnInfo
from app.entities.metric_info import MetricInfo
from app.entities.value_info import ValueInfo


class ColumnInfoState(TypedDict):
    name: str
    type: str
    role: str
    examples: list
    description: str
    alias: list[str]


class TableInfoState(TypedDict):
    name: str
    role: str
    description: str
    columns: list[ColumnInfoState]


class MetricInfoState(TypedDict):
    name: str
    description: str
    relevant_columns: list[str]
    alias: list[str]


class DateInfoState(TypedDict):
    date: str
    weekday: str
    quarter: str


class DBInfoState(TypedDict):
    dialect: str
    version: str


class DataAgentState(TypedDict):
    query: str  # User query
    keywords: list[str]  # Keywords from user query

    retrieved_columns: list[ColumnInfo]  # Retrieved column info
    retrieved_values: list[ValueInfo]  # Retrieved value info
    retrieved_metrics: list[MetricInfo]  # Retrieved metric info

    table_infos: list[TableInfoState]  # Table info
    metric_infos: list[MetricInfoState]  # Metric info

    date_info: DateInfoState  # Date info
    db_info: DBInfoState  # Database info

    sql: str  # Generated SQL

    error: str  # SQL validation error message
    correction_attempts: int  # Number of SQL correction attempts
