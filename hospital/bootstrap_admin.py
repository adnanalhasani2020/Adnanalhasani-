"""Create the first hospital administrator account interactively.

Run from the repository root:
    python -m hospital.bootstrap_admin
The command refuses to run if an authentication user already exists.
"""
from __future__ import annotations

import getpass
import os
from datetime import datetime, timezone

from .core import HospitalDB
from .security import AuthStore


def main() -> int:
    db_path = os.environ.get("HOSPITAL_DB_PATH", "hospital.sqlite3")
    database = HospitalDB(db_path)
    store = AuthStore(database)
    with database.connect() as conn:
        count = conn.execute("SELECT COUNT(*) FROM auth_users").fetchone()[0]
    if count:
        print("Bootstrap refused: an authentication user already exists.")
        return 2

    username = input("Initial administrator username: ").strip()
    password = getpass.getpass("Administrator password (12+ characters): ")
    confirmation = getpass.getpass("Confirm password: ")
    if password != confirmation:
        print("Passwords do not match.")
        return 2
    if len(password) < 12:
        print("Password must contain at least 12 characters.")
        return 2

    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    try:
        user_id = store.create_user(username=username, password=password, role="admin",
                                    at=now, actor="bootstrap")
    except (ValueError, Exception) as exc:
        print(f"Could not create administrator: {exc}")
        return 2
    print(f"Administrator created (user id {user_id}). Keep the password private.")
    print("Start the API with HOSPITAL_AUTH_MODE=users and the same HOSPITAL_DB_PATH.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
