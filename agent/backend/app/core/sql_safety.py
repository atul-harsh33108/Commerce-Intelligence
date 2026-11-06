import re


FORBIDDEN_SQL = re.compile(
    r"\b(ALTER|CREATE|DELETE|DROP|GRANT|INSERT|REPLACE|REVOKE|TRUNCATE|UPDATE|USE|CALL|LOAD|LOCK|UNLOCK)\b",
    re.IGNORECASE,
)


def sanitize_sql(sql: str) -> str:
    cleaned = sql.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:sql)?\s*", "", cleaned, flags=re.IGNORECASE)
        cleaned = re.sub(r"\s*```$", "", cleaned)
    cleaned = cleaned.strip()
    if cleaned.endswith(";"):
        cleaned = cleaned[:-1].strip()
    return cleaned


def validate_readonly_sql(sql: str) -> str:
    cleaned = sanitize_sql(sql)
    if not cleaned:
        raise ValueError("SQL is empty")

    statements = [part.strip() for part in cleaned.split(";") if part.strip()]
    if len(statements) != 1:
        raise ValueError("Only one SQL statement is allowed")

    first_token = statements[0].split(None, 1)[0].lower()
    if first_token not in {"select", "with"}:
        raise ValueError("Only SELECT queries are allowed")

    if FORBIDDEN_SQL.search(statements[0]):
        raise ValueError("Only read-only SQL is allowed")

    return statements[0]
