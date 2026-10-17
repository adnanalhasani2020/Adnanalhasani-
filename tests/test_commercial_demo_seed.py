from concurrent.futures import ThreadPoolExecutor
from threading import Barrier

import pytest

from agent_core.commercial_demo_seed import seed_demo
from agent_core.commerce_checkout import CommercialCheckoutApplication
from agent_core.persistence import connect_database


def test_demo_seed_creates_one_sellable_product_and_login(tmp_path):
    path = tmp_path / "commercial-demo.sqlite3"
    result = seed_demo(path)
    db = connect_database(path)
    try:
        assert result["username"] == "demo_buyer"
        assert len(result["password"]) >= 20
        offerings = db.execute("SELECT offering_id, activity_id FROM offerings WHERE state='active'").fetchall()
        assert len(offerings) == 1
        offering_id, activity_id = offerings[0]
        catalog = CommercialCheckoutApplication().list_sellable_offerings(
            db, activity_id=activity_id, scope_key="demo-store",
            location_ref="front",
        )
        assert len(catalog) == 1
        assert catalog[0]["product_name"] == "منتج تجريبي"
        assert catalog[0]["available_quantity"] == 10
        assert catalog[0]["unit_price_minor"] == 990
        assert catalog[0]["currency_code"] == "SAR"
    finally:
        db.close()


def test_demo_seed_refuses_nonempty_business_database(tmp_path):
    path = tmp_path / "not-empty.sqlite3"
    db = connect_database(path)
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES('existing-person','active','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')"
    )
    db.commit()
    db.close()

    with pytest.raises(ValueError, match="separate disposable database"):
        seed_demo(path)
    db = connect_database(path)
    try:
        assert db.execute("SELECT COUNT(*) FROM persons").fetchone()[0] == 1
        assert db.execute("SELECT COUNT(*) FROM products").fetchone()[0] == 0
    finally:
        db.close()


def test_concurrent_demo_seed_attempts_are_serialized(tmp_path):
    path = tmp_path / "concurrent-commercial-demo.sqlite3"
    barrier = Barrier(2)

    def attempt_seed():
        barrier.wait(timeout=3)
        try:
            return seed_demo(path)
        except ValueError as exc:
            return exc

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: attempt_seed(), range(2)))

    successes = [item for item in results if isinstance(item, dict)]
    refusals = [item for item in results if isinstance(item, ValueError)]
    assert len(successes) == 1
    assert len(refusals) == 1
    assert "separate disposable database" in str(refusals[0])

    db = connect_database(path)
    try:
        assert db.execute("SELECT COUNT(*) FROM commercial_credentials").fetchone()[0] == 1
        assert db.execute("SELECT COUNT(*) FROM products").fetchone()[0] == 1
        assert db.execute("SELECT COUNT(*) FROM offerings").fetchone()[0] == 1
    finally:
        db.close()
