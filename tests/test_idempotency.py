import sqlite3
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from agent_core.idempotency import (
    IdempotencyKeyConflict, execute_idempotently, initialize_idempotency,
)


def connection(path=":memory:"):
    db = sqlite3.connect(path, timeout=5)
    db.execute("CREATE TABLE IF NOT EXISTS effects (value TEXT NOT NULL)")
    initialize_idempotency(db)
    return db


def test_first_request_executes_and_retry_returns_persisted_result(tmp_path):
    path = str(tmp_path / "app.sqlite")
    calls = []
    first = connection(path)
    result = execute_idempotently(first, "create-record", "key-1", {"name": "A"},
        lambda db: (calls.append(1), db.execute("INSERT INTO effects VALUES (?)", ("A",)),
                   {"record_id": "r1"})[-1])
    first.close()
    reopened = connection(path)
    replay = execute_idempotently(reopened, "create-record", "key-1", {"name": "A"},
        lambda db: pytest.fail("replayed operation must not execute"))
    assert result == replay == {"record_id": "r1"}
    assert calls == [1]
    assert reopened.execute("SELECT count(*) FROM effects").fetchone()[0] == 1
    reopened.close()


def test_same_key_with_different_request_is_rejected():
    db = connection()
    execute_idempotently(db, "op", "key", {"amount": 1}, lambda c: {"ok": True})
    with pytest.raises(IdempotencyKeyConflict):
        execute_idempotently(db, "op", "key", {"amount": 2},
                             lambda c: pytest.fail("must not execute"))
    db.close()


def test_scope_is_part_of_key_identity():
    db = connection()
    a = execute_idempotently(db, "operation-a", "same", {"v": 1}, lambda c: {"v": "a"})
    b = execute_idempotently(db, "operation-b", "same", {"v": 1}, lambda c: {"v": "b"})
    assert (a, b) == ({"v": "a"}, {"v": "b"})
    db.close()


def test_failed_operation_rolls_back_effect_and_receipt_then_can_retry():
    db = connection()
    def broken(c):
        c.execute("INSERT INTO effects VALUES (?)", ("not-committed",))
        raise RuntimeError("transient failure")
    with pytest.raises(RuntimeError, match="transient failure"):
        execute_idempotently(db, "op", "key", {"v": 1}, broken)
    assert db.execute("SELECT count(*) FROM effects").fetchone()[0] == 0
    result = execute_idempotently(db, "op", "key", {"v": 1},
        lambda c: (c.execute("INSERT INTO effects VALUES (?)", ("committed",)), {"ok": 1})[-1])
    assert result == {"ok": 1}
    assert db.execute("SELECT value FROM effects").fetchall() == [("committed",)]
    db.close()


def test_concurrent_same_request_commits_one_effect(tmp_path):
    path = str(tmp_path / "concurrent.sqlite")
    setup = connection(path)
    setup.close()
    barrier = threading.Barrier(2)
    def worker():
        db = sqlite3.connect(path, timeout=5)
        initialize_idempotency(db)
        barrier.wait()
        try:
            return execute_idempotently(db, "op", "key", {"v": 1},
                lambda c: (c.execute("INSERT INTO effects VALUES (?)", ("once",)),
                           {"result": "done"})[-1])
        finally:
            db.close()
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: worker(), range(2)))
    assert results == [{"result": "done"}, {"result": "done"}]
    verify = sqlite3.connect(path)
    assert verify.execute("SELECT count(*) FROM effects").fetchone()[0] == 1
    assert verify.execute("SELECT count(*) FROM idempotency_receipts").fetchone()[0] == 1
    verify.close()