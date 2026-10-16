"""Admin CLI to configure an Offering price without inventing defaults.

Example:
  PYTHONPATH=src python tools/set_offering_price.py \
    --offering-id <uuid> --unit-price-minor 1250 --currency-code SAR \
    --actor-context-ref <authorized-actor-context>
"""
from __future__ import annotations

import argparse
import json

from agent_core.offering_price_application import OfferingPriceApplication
from agent_core.persistence import connect_database


def main():
    parser = argparse.ArgumentParser(description="Set an audited effective Offering price")
    parser.add_argument("--database", default="commercial-mvp.sqlite3")
    parser.add_argument("--offering-id", required=True)
    parser.add_argument("--unit-price-minor", required=True, type=int)
    parser.add_argument("--currency-code", required=True, choices=("SAR", "YER", "USD"))
    parser.add_argument("--actor-context-ref", required=True)
    parser.add_argument("--effective-from")
    parser.add_argument("--effective-to")
    args = parser.parse_args()

    db = connect_database(args.database)
    try:
        result = OfferingPriceApplication().set_price(
            db, offering_id=args.offering_id,
            unit_price_minor=args.unit_price_minor,
            currency_code=args.currency_code,
            actor_context_ref=args.actor_context_ref,
            effective_from=args.effective_from,
            effective_to=args.effective_to,
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
    finally:
        db.close()


if __name__ == "__main__":
    main()
