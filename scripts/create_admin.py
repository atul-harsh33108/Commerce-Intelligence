#!/usr/bin/env python3
"""Create (or promote) the local development ADMIN account.

Separate from the loader and from any Flyway seed, on purpose. A migration that inserts an admin
account with a known password is a migration that runs in production too - and the fact that it is
"only the dev profile" is a comment, not a mechanism. This is a script somebody runs deliberately,
against a database they name, and it says so in its output.

    python scripts/create_admin.py

Idempotent: run it twice and the second run promotes the existing row rather than failing.
"""

from __future__ import annotations

import argparse
import sys

try:
    import psycopg
except ImportError:
    print("psycopg is required:  pip install 'psycopg[binary]'", file=sys.stderr)
    raise SystemExit(2)

DSN = "postgresql://userdb:userdb@localhost:5433/userdb?connect_timeout=5"

USERNAME = "admin"
EMAIL = "admin@example.invalid"

# bcrypt cost 10 of "admin-dev-password".
#
# A LITERAL HASH OF A KNOWN PASSWORD, and that is only acceptable because of what this script is: a
# development convenience for a database that lives in a container on a laptop. Anything reachable
# from outside a laptop needs a password nobody has read in a README - which is why the value is
# stated here rather than hidden, so nobody can mistake it for a secret.
PASSWORD_HASH = "$2a$10$ZjlM6OdSbMi3S8fvKWCUjubT3EJdpNXdV5f/Lrl2iOxpOEl1SrP6e"
PLAINTEXT = "admin-dev-password"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--dsn", default=DSN)
    args = parser.parse_args()

    with psycopg.connect(args.dsn) as conn, conn.cursor() as cur:
        cur.execute("""
            INSERT INTO users (username, email, password, role, version, created_at, updated_at)
            VALUES (%s, %s, %s, 'ADMIN', 0, now(), now())
            ON CONFLICT (username) DO UPDATE
                SET role = 'ADMIN', password = EXCLUDED.password, updated_at = now()
            RETURNING id, role
        """, (USERNAME, EMAIL, PASSWORD_HASH))
        user_id, role = cur.fetchone()
        conn.commit()

    print(f"{USERNAME} is user id {user_id} with role {role}")
    print(f"password: {PLAINTEXT}   (development only - see the comment in this file)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
