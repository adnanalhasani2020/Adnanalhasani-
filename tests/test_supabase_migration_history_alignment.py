from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase/migrations"
ARCHIVE = ROOT / "supabase/migrations_archive/2026-11-06"

DEPLOYED_FORWARD_MIGRATIONS = (
    "20261106001452_commercial_personal_work_notes_forward_20261106.sql",
    "20261106001610_commercial_advertising_offers_forward_20261106.sql",
    "20261106102423_commercial_customer_order_reservations_forward_20261106.sql",
    "20261106102440_general_customer_supply_requests_forward_20261106.sql",
)

SUPERSEDED_HISTORICAL_MIGRATIONS = (
    "20261105140000_commercial_customer_order_reservations.sql",
    "20261105160000_general_customer_supply_requests.sql",
    "20261105180000_commercial_personal_work_notes.sql",
    "20261105190000_commercial_advertising_offers.sql",
    "20261105200000_fix_customer_supply_request_list_limit.sql",
)


def test_active_migration_chain_matches_the_deployed_forward_versions():
    active_names = {path.name for path in MIGRATIONS.glob("*.sql")}
    for name in DEPLOYED_FORWARD_MIGRATIONS:
        assert name in active_names, f"deployed migration source missing: {name}"
    for name in SUPERSEDED_HISTORICAL_MIGRATIONS:
        assert name not in active_names, f"superseded migration must not run again: {name}"
        assert (ARCHIVE / name).is_file(), f"historical source must remain archived: {name}"


def test_active_migration_versions_are_unique_and_well_formed():
    versions = []
    for path in MIGRATIONS.glob("*.sql"):
        match = re.match(r"^(\\d{14})_", path.name)
        assert match, f"migration filename must start with a 14-digit version: {path.name}"
        versions.append(match.group(1))
    assert len(versions) == len(set(versions)), "duplicate active migration version detected"
