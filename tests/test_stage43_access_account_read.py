import uuid

import pytest

from agent_core.access_account_read import PersistedAccessAccountReader
from agent_core.persistence import connect_database

STAMP = "2026-10-10T21:00:00Z"


def seed_person(db):
    person_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (person_id, "active", STAMP, STAMP),
    )
    return person_id


def seed_account(db, person_id, *, state="active", created_at=STAMP):
    account_id = str(uuid.uuid4())
    db.execute(
        """INSERT INTO access_accounts(
            access_account_id,person_id,state,created_at,updated_at,version_no
        ) VALUES(?,?,?,?,?,?)""",
        (account_id, person_id, state, created_at, created_at, 1),
    )
    return account_id


def test_access_account_read_restores_persisted_record_after_reopen(tmp_path):
    path = tmp_path / "accounts.sqlite"
    db = connect_database(path)
    person = seed_person(db)
    account = seed_account(db, person, state="suspended")
    db.commit()
    db.close()

    reopened = connect_database(path)
    record = PersistedAccessAccountReader().get(reopened, account)
    assert record.access_account_id == uuid.UUID(account)
    assert record.person_id == uuid.UUID(person)
    assert record.state == "suspended"
    assert record.version_no == 1
    assert PersistedAccessAccountReader().get(reopened, str(uuid.uuid4())) is None
    reopened.close()


def test_account_listing_is_person_scoped_and_deterministic():
    db = connect_database()
    person, other = seed_person(db), seed_person(db)
    later = seed_account(db, person, created_at="2026-10-10T21:02:00Z")
    earlier = seed_account(db, person, created_at="2026-10-10T21:01:00Z")
    seed_account(db, other)
    db.commit()

    rows = PersistedAccessAccountReader().list_for_person(db, person)
    assert [str(row.access_account_id) for row in rows] == [earlier, later]
    assert all(str(row.person_id) == person for row in rows)
    assert PersistedAccessAccountReader().list_for_person(db, str(uuid.uuid4())) == ()
    db.close()


def test_invalid_access_account_or_person_uuid_is_rejected():
    db = connect_database()
    reader = PersistedAccessAccountReader()
    with pytest.raises(ValueError, match="access_account_id must be a UUID"):
        reader.get(db, "invalid")
    with pytest.raises(ValueError, match="person_id must be a UUID"):
        reader.list_for_person(db, "invalid")
    db.close()
