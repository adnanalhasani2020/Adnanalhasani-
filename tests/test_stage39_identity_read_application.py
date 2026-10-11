import uuid

import pytest

from agent_core.identity_read import PersistedIdentityReader
from agent_core.persistence import connect_database

STAMP = "2026-10-10T12:00:00Z"


def seed_person(
    db, *, state="active", canonical_person_id=None,
    created_at=STAMP, updated_at=STAMP, version_no=1,
):
    person_id = str(uuid.uuid4())
    db.execute(
        "INSERT INTO persons(person_id,state,canonical_person_id,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?)",
        (person_id, state, canonical_person_id, created_at, updated_at, version_no),
    )
    db.commit()
    return person_id


def test_get_person_restores_canonical_reference_timestamps_and_missing_is_none():
    db = connect_database()
    canonical_id = seed_person(db)
    person_id = seed_person(
        db, state="suspended", canonical_person_id=canonical_id,
        created_at="2026-10-09T09:00:00Z",
        updated_at="2026-10-10T12:34:56Z", version_no=7,
    )
    reader = PersistedIdentityReader()
    record = reader.get_person(db, person_id)
    assert record.person_id == uuid.UUID(person_id)
    assert record.state == "suspended"
    assert record.canonical_person_id == uuid.UUID(canonical_id)
    assert record.version_no == 7
    assert record.created_at == "2026-10-09T09:00:00Z"
    assert record.updated_at == "2026-10-10T12:34:56Z"
    assert reader.get_person(db, str(uuid.uuid4())) is None
    db.close()


def test_identifier_listing_is_person_scoped_and_tie_breaks_by_identifier_id():
    db = connect_database()
    person = seed_person(db)
    other = seed_person(db)
    ids = sorted([str(uuid.uuid4()), str(uuid.uuid4())])
    for identifier_id, value in [(ids[1], "later-id"), (ids[0], "earlier-id")]:
        db.execute(
            """INSERT INTO identifiers(identifier_id,person_id,identifier_type,normalized_value,
               uniqueness_scope,state,created_at,updated_at,version_no)
               VALUES(?,?,?,?,?,?,?,?,?)""",
            (identifier_id, person, "external_ref", value, "test", "issued", STAMP, STAMP, 1),
        )
    db.execute(
        """INSERT INTO identifiers(identifier_id,person_id,identifier_type,normalized_value,
           uniqueness_scope,state,created_at,updated_at,version_no)
           VALUES(?,?,?,?,?,?,?,?,?)""",
        (str(uuid.uuid4()), other, "external_ref", "other", "test", "issued", STAMP, STAMP, 1),
    )
    db.commit()
    rows = PersistedIdentityReader().list_identifiers(db, person)
    assert [str(row.identifier_id) for row in rows] == ids
    assert [row.normalized_value for row in rows] == ["earlier-id", "later-id"]
    assert all(str(row.person_id) == person for row in rows)
    db.close()


def test_missing_person_and_person_without_identifiers_are_distinguishable():
    db = connect_database()
    reader = PersistedIdentityReader()
    person = seed_person(db)
    missing = str(uuid.uuid4())
    assert reader.get_person(db, person) is not None
    assert reader.list_identifiers(db, person) == ()
    assert reader.get_person(db, missing) is None
    assert reader.list_identifiers(db, missing) == ()
    db.close()


def test_invalid_person_id_is_rejected_for_both_read_paths():
    db = connect_database()
    reader = PersistedIdentityReader()
    with pytest.raises(ValueError, match="person_id must be a UUID"):
        reader.get_person(db, "not-a-uuid")
    with pytest.raises(ValueError, match="person_id must be a UUID"):
        reader.list_identifiers(db, "not-a-uuid")
    db.close()
