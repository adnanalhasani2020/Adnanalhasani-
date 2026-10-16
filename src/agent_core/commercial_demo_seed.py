"""Create a disposable local demo catalog for the commercial MVP.

This is development-only tooling. It refuses to add demo data to a non-empty
business database and requires an explicit command-line opt-in.
"""
from __future__ import annotations

import argparse
import secrets
import sqlite3
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

from agent_core.application import InventoryPositionApplication, ProductApplication
from agent_core.commercial_identity import CommercialIdentityApplication
from agent_core.offering_application import OfferingApplication
from agent_core.offering_price_application import OfferingPriceApplication
from agent_core.persistence import connect_database


def seed_demo(database_path: str | Path) -> dict:
    """Seed one demo buyer, one active offering, an effective price, and opening stock."""
    db = connect_database(database_path)
    try:
        # Serialize seed attempts before checking emptiness; otherwise two concurrent
        # invocations can both observe an empty database and race to create demo data.
        db.execute("BEGIN IMMEDIATE")
        for table in ("persons", "commercial_credentials", "activities", "products", "offerings"):
            if db.execute(f"SELECT 1 FROM {table} LIMIT 1").fetchone() is not None:
                raise ValueError(
                    f"Refusing to seed demo data: table {table} is not empty. "
                    "Use a separate disposable database."
                )

        now = datetime.now(timezone.utc)
        opening_at = now - timedelta(minutes=1)
        stamp = now.isoformat(timespec="microseconds").replace("+00:00", "Z")
        opened = opening_at.isoformat(timespec="microseconds").replace("+00:00", "Z")
        actor_context = "local-commercial-demo-seed"
        identity = CommercialIdentityApplication()
        username = "demo_buyer"
        password = secrets.token_urlsafe(18)
        registered = identity.register(db, username=username, password=password, now=now)
        person_id = registered["person_id"]

        activity_id = str(uuid4())
        db.execute(
            """INSERT INTO activities(
                   activity_id,owner_person_id,state,created_at,updated_at
               ) VALUES(?,?,'active',?,?)""",
            (activity_id, person_id, stamp, stamp),
        )
        products = ProductApplication()
        product = products.create_product(
            db, "منتج تجريبي", actor_context_ref=actor_context, now=now,
        )
        products.transition_product(
            db, product.product.id, "activate", expected_version=1,
            actor_context_ref=actor_context, now=now,
        )

        offerings = OfferingApplication()
        offering = offerings.create_offering(
            db, product_id=product.product.id, activity_id=activity_id,
            effective_from=opened, actor_context_ref=actor_context, now=now,
        )
        offerings.transition_offering(
            db, offering.offering.id, "activate", expected_version=1,
            actor_context_ref=actor_context, now=now,
        )
        OfferingPriceApplication().set_price(
            db, offering_id=offering.offering.id, unit_price_minor=990,
            currency_code="SAR", actor_context_ref=actor_context,
            effective_from=opened, now=now,
        )
        InventoryPositionApplication().create_opening_stock(
            db, activity_id=activity_id, product_id=product.product.id,
            offering_id=offering.offering.id, scope_key="demo-store",
            location_ref="front", quantity_minor=10,
            actor_context_ref=actor_context, source_ref="local-demo-opening-stock",
            idempotency_key="local-commercial-demo-opening-stock-v1",
            opening_at=opening_at, now=now,
        )
        # Domain applications use savepoints when a caller owns the transaction.
        # Commit the complete seeded catalog only after every fact was written.
        db.commit()
        return {
            "database_path": str(database_path),
            "username": username,
            "password": password,
            "product_name": product.product.name,
            "quantity": 10,
            "unit_price_minor": 990,
            "currency_code": "SAR",
        }
    finally:
        db.close()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", default="commercial-demo.sqlite3")
    parser.add_argument(
        "--allow-demo-data", action="store_true",
        help="Required explicit opt-in; never run this against a real business database.",
    )
    args = parser.parse_args()
    if not args.allow_demo_data:
        parser.error("pass --allow-demo-data only for a disposable local database")
    try:
        result = seed_demo(args.database)
    except (ValueError, sqlite3.DatabaseError) as exc:
        parser.error(str(exc))
    print("Local demo catalog created. DEVELOPMENT USE ONLY.")
    print(f"Database: {result['database_path']}")
    print(f"Login username: {result['username']}")
    print(f"Login password (shown once): {result['password']}")
    print(
        f"Product: {result['product_name']} | stock: {result['quantity']} | "
        f"price: {result['unit_price_minor']} {result['currency_code']} minor units"
    )
    print("Do not expose this demo database or account to the public internet.")


if __name__ == "__main__":
    main()
