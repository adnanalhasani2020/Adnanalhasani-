import uuid

import pytest

from agent_core.identity_read import PersistedIdentityReader
from agent_core.persistence import connect_database

STAMP = "2026-10-10T12:00:00Z"


def seed_person(db, *, state="active", canonical_person_id=None):
    person_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO persons(person_id,state,canonical_person_id,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?)",
        (person_id, state, canonical_person_id, STAMP, STAMP, 1),
    )
    db.commit()
    return person_id


def test_get_person_restores_persisted_fields_and_missing_is_none():
    db = connect_database()
    person_id = seed_person(db, state="suspended")
    reader = PersistedIdentityReader()
    record = reader.get_person(db, person_id)
    assert record.person_id == uuid.UUID(person_id)
    assert record.state == "suspended"
    assert record.version_no == 1
    assert record.created_at == STAMP
    assert reader.get_person(db, str(uuid.uuid4())) is None
    db.close()


def test_identifier_listing_is_person_scoped_and_deterministic():
    db = connect_database()
    person = seed_person(db)
    other = seed_person(db)
    ids = [str(uuid.uuid4()), str(uuid.uuid4())]
    for identifier_id, value, created in [(ids[1], "z-value", "2026-10-10T12:02:00Z"),
                                          (ids[0], "a-value", "2026-10-10T12:01:00Z")]:
        db.execute(
            """INSERT INTO identifiers(identifier_id,person_id,identifier_type,normalized_value,
               uniqueness_scope,state,created_at,updated_at,version_no)
               VALUES(?,?,?,?,?,?,?,?,?)""",
            (identifier_id, person, "external_ref", value, "test", "issued", created, created, 1),
        )
    db.execute(
        """INSERT INTO identifiers(identifier_id,person_id,identifier_type,normalized_value,
           uniqueness_scope,state,created_at,updated_at,version_no)
           VALUES(?,?,?,?,?,?,?,?,?)""",
        (str(uuid.uuid4()), other, "external_ref", "other", "test", "issued", STAMP, STAMP, 1),
    )
    db.commit()
    rows = PersistedIdentityReader().list_identifiers(db, person)
    assert [row.normalized_value for row in rows] == ["a-value", "z-value"]
    assert all(str(row.person_id) == person for row in rows)
    assert PersistedIdentityReader().list_identifiers(db, str(uuid.uuid4())) == ()
    db.close()


def test_invalid_person_id_is_rejected():
    db = connect_database()
    reader = PersistedIdentityReader()
    with pytest.raises(ValueError, match="person_id must be a UUID"):
        reader.get_person(db, "not-a-uuid")
    with pytest.raises(ValueError, match="person_id must be a UUID"):
        reader.list_identifiers(db, "not-a-uuid")
    db.close()