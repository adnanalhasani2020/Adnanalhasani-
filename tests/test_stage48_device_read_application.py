"""Read-only persisted Device query contract."""
import uuid

import pytest

from agent_core.device_read_application import DeviceReadApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def uid():
    return str(uuid.uuid4())


def seed_person(db, person_id=None):
    key = person_id or uid()
    db.execute(
        "INSERT INTO persons(person_id,state,created_at,updated_at) VALUES(?,?,?,?)",
        (key, "active", STAMP, STAMP),
    )
    return key


def seed_account(db, person_id, account_id=None):
    key = account_id or uid()
    db.execute(
        "INSERT INTO access_accounts(access_account_id,person_id,state,created_at,updated_at) "
        "VALUES(?,?,?,?,?)",
        (key, person_id, "active", STAMP, STAMP),
    )
    return key


def seed_device(db, *, device_id=None, person_id=None, account_id=None,
                fingerprint=None, state="active", created_at=STAMP,
                updated_at=STAMP, provenance_id=None, version_no=1):
    key = device_id or uid()
    db.execute(
        "INSERT INTO devices(device_id,device_fingerprint_ref,state,person_id,"
        "access_account_id,created_at,updated_at,provenance_id,version_no) "
        "VALUES(?,?,?,?,?,?,?,?,?)",
        (key, fingerprint or f"fingerprint:{key}", state, person_id, account_id,
         created_at, updated_at, provenance_id, version_no),
    )
    return key


def test_get_restores_all_device_fields_and_preserves_nullable_references():
    db = connect_database()
    person = seed_person(db)
    account = seed_account(db, person)
    device = seed_device(
        db, person_id=person, account_id=account, state="retired",
        created_at="2026-10-09T10:00:00Z", updated_at=STAMP,
        provenance_id="source:device-import", version_no=4,
    )
    db.commit()

    result = DeviceReadApplication().get(db, device)

    assert result.device_id == uuid.UUID(device)
    assert result.device_fingerprint_ref == f"fingerprint:{device}"
    assert result.state == "retired"
    assert result.person_id == uuid.UUID(person)
    assert result.access_account_id == uuid.UUID(account)
    assert result.created_at == "2026-10-09T10:00:00Z"
    assert result.updated_at == STAMP
    assert result.provenance_id == "source:device-import"
    assert result.version_no == 4

    no_owner = seed_device(db)
    db.commit()
    result_without_refs = DeviceReadApplication().get(db, no_owner)
    assert result_without_refs.person_id is None
    assert result_without_refs.access_account_id is None
    assert result_without_refs.provenance_id is None
    db.close()


@pytest.mark.parametrize("value", ["", "not-a-uuid", None, 12])
def test_get_rejects_invalid_device_uuid(value):
    db = connect_database()
    with pytest.raises(ValidationError, match="Device identifier must be a valid UUID"):
        DeviceReadApplication().get(db, value)
    db.close()


def test_get_distinguishes_missing_device_from_empty_listing():
    db = connect_database()
    person = seed_person(db)
    app = DeviceReadApplication()

    assert app.list_for_person(db, person) == ()
    with pytest.raises(ValidationError, match="Device does not exist"):
        app.get(db, uid())
    db.close()


def test_list_for_person_isolated_and_deterministic():
    db = connect_database()
    person_a = seed_person(db)
    person_b = seed_person(db)
    first = seed_device(db, person_id=person_a, created_at="2026-10-10T10:00:00Z")
    second = seed_device(db, person_id=person_a, created_at="2026-10-10T10:00:00Z")
    seed_device(db, person_id=person_b)
    db.commit()

    result = DeviceReadApplication().list_for_person(db, person_a)

    assert tuple(str(row.device_id) for row in result) == tuple(sorted((first, second)))
    assert all(row.person_id == uuid.UUID(person_a) for row in result)
    assert DeviceReadApplication().list_for_person(db, uid()) == ()
    db.close()


def test_list_for_access_account_isolated_and_deterministic():
    db = connect_database()
    person = seed_person(db)
    account_a = seed_account(db, person)
    account_b = seed_account(db, person)
    first = seed_device(db, account_id=account_a, created_at="2026-10-10T10:00:00Z")
    second = seed_device(db, account_id=account_a, created_at="2026-10-10T10:00:00Z")
    seed_device(db, account_id=account_b)
    db.commit()

    result = DeviceReadApplication().list_for_access_account(db, account_a)

    assert tuple(str(row.device_id) for row in result) == tuple(sorted((first, second)))
    assert all(row.access_account_id == uuid.UUID(account_a) for row in result)
    assert DeviceReadApplication().list_for_access_account(db, uid()) == ()
    db.close()


@pytest.mark.parametrize(
    ("method", "value", "message"),
    [
        ("list_for_person", "invalid", "Person identifier must be a valid UUID"),
        ("list_for_access_account", "invalid", "Access Account identifier must be a valid UUID"),
    ],
)
def test_scoped_lists_validate_their_own_reference_types(method, value, message):
    db = connect_database()
    with pytest.raises(ValidationError, match=message):
        getattr(DeviceReadApplication(), method)(db, value)
    db.close()


def test_reads_are_non_mutating_and_records_survive_database_reopen(tmp_path):
    path = tmp_path / "devices.sqlite"
    db = connect_database(path)
    person = seed_person(db)
    account = seed_account(db, person)
    device = seed_device(db, person_id=person, account_id=account)
    db.commit()
    before = db.execute(
        "SELECT device_id,device_fingerprint_ref,state,person_id,access_account_id,"
        "created_at,updated_at,provenance_id,version_no FROM devices WHERE device_id=?",
        (device,),
    ).fetchone()

    app = DeviceReadApplication()
    app.get(db, device)
    app.list_for_person(db, person)
    app.list_for_access_account(db, account)
    after = db.execute(
        "SELECT device_id,device_fingerprint_ref,state,person_id,access_account_id,"
        "created_at,updated_at,provenance_id,version_no FROM devices WHERE device_id=?",
        (device,),
    ).fetchone()
    assert after == before
    db.close()

    reopened = connect_database(path)
    assert app.get(reopened, device).device_id == uuid.UUID(device)
    assert tuple(row.device_id for row in app.list_for_person(reopened, person)) == (uuid.UUID(device),)
    assert tuple(row.device_id for row in app.list_for_access_account(reopened, account)) == (uuid.UUID(device),)
    assert reopened.execute(
        "SELECT device_id,device_fingerprint_ref,state,person_id,access_account_id,"
        "created_at,updated_at,provenance_id,version_no FROM devices WHERE device_id=?",
        (device,),
    ).fetchone() == before
    reopened.close()
