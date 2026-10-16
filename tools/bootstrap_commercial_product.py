"""Create one explicitly supplied commercial product, Offering, opening stock, and price.

This is an operator-run local setup command. It never invents product, price, or
stock values; all commercial facts are supplied as command-line arguments.
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from uuid import uuid4

from agent_core.application import InventoryPositionApplication, ProductApplication
from agent_core.offering_application import OfferingApplication
from agent_core.offering_price_application import OfferingPriceApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


def main():
    parser = argparse.ArgumentParser(description="Bootstrap one real stock-tracked Offering")
    parser.add_argument("--database", default="commercial-mvp.sqlite3")
    parser.add_argument("--owner-person-id", required=True, help="Existing active Person who owns the Activity")
    parser.add_argument("--actor-context-ref", required=True, help="Operator/audit context reference")
    parser.add_argument("--product-name", required=True)
    parser.add_argument("--quantity", required=True, type=int)
    parser.add_argument("--scope-key", required=True)
    parser.add_argument("--location-ref")
    parser.add_argument("--unit-price-minor", required=True, type=int)
    parser.add_argument("--currency-code", required=True, choices=("SAR", "YER", "USD"))
    args = parser.parse_args()

    if args.quantity < 0:
        parser.error("--quantity must be non-negative")
    if not args.scope_key.strip() or not args.actor_context_ref.strip():
        parser.error("--scope-key and --actor-context-ref must be non-empty")

    db = connect_database(args.database)
    stamp = datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")
    activity_id = str(uuid4())
    try:
        owner = db.execute(
            "SELECT state FROM persons WHERE person_id=?", (args.owner_person_id,)
        ).fetchone()
        if owner is None or owner[0] != "active":
            raise ValidationError("--owner-person-id must reference an existing active Person")
        db.execute(
            """INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at)
               VALUES(?,?,'active',?,?)""",
            (activity_id, args.owner_person_id, stamp, stamp),
        )

        products = ProductApplication()
        product = products.create_product(
            db, args.product_name, actor_context_ref=args.actor_context_ref, now=stamp
        )
        products.transition_product(
            db, product.product.id, "activate", expected_version=1,
            actor_context_ref=args.actor_context_ref, now=stamp,
        )
        offerings = OfferingApplication()
        offering = offerings.create_offering(
            db, product_id=product.product.id, activity_id=activity_id,
            effective_from=stamp, actor_context_ref=args.actor_context_ref, now=stamp,
        )
        offerings.transition_offering(
            db, offering.offering.id, "activate", expected_version=1,
            actor_context_ref=args.actor_context_ref, now=stamp,
        )
        InventoryPositionApplication().create_opening_stock(
            db, activity_id=activity_id, product_id=product.product.id,
            offering_id=offering.offering.id, location_ref=args.location_ref,
            scope_key=args.scope_key, quantity_minor=args.quantity,
            actor_context_ref=args.actor_context_ref,
            source_ref=f"commercial-mvp-opening:{offering.offering.id}",
            idempotency_key=f"commercial-mvp-opening:{offering.offering.id}",
            opening_at=stamp, now=stamp,
        )
        price = OfferingPriceApplication().set_price(
            db, offering_id=offering.offering.id,
            unit_price_minor=args.unit_price_minor, currency_code=args.currency_code,
            actor_context_ref=args.actor_context_ref, effective_from=stamp, now=stamp,
        )
        db.commit()
        print(json.dumps({
            "activity_id": activity_id,
            "product_id": str(product.product.id),
            "offering_id": str(offering.offering.id),
            "opening_quantity": args.quantity,
            "scope_key": args.scope_key,
            "location_ref": args.location_ref,
            "price": price,
        }, ensure_ascii=False, indent=2))
    finally:
        db.close()


if __name__ == "__main__":
    main()
