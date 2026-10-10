"""Integration tests for explicit provenance persistence and retrieval."""
import uuid

import pytest

from agent_core.domain_provenance import ProvenanceRecordApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError

STAMP = "2026-10-10T12:00:00Z"


def test_provenance_record_persists_and_can_be_restored_after_reopen(tmp_path):
    path = tmp_path / "provenance.sqlite"
    db = connect_database(path)
    provenance_id = uuid.uuid4()
    app = ProvenanceRecordApplication()
    record = app.append(
        db, source_type="external_document", source_ref="document:abc",
        source_version_ref="revision:3", as_of_at=STAMP, derivation_type="imported",
        provenance_id=provenance_id, now=STAMP,
    )
    assert record.provenance_id == provenance_id
    db.close()

    db = connect_database(path)
    restored = app.get(db, provenance_id)
    assert restored == record
    assert app.list_for_source(
        db, source_type="external_document", source_ref="document:abc"
    ) == (record,)
    db.close()


@pytest.mark.parametrize(
    "field,value",
    [
        ("source_type", ""),
        ("source_ref", " "),
        ("derivation_type", ""),
    ],
)
def test_provenance_append_requires_explicit_source_and_derivation(field, value):
    db = connect_database()
    values = {
        "source_type": "external_document",
        "source_ref": "document:abc",
        "derivation_type": "imported",
        "now": STAMP,
    }
    values[field] = value
    with pytest.raises(ValidationError, match="is required"):
        ProvenanceRecordApplication().append(db, **values)
    assert db.execute("SELECT COUNT(*) FROM provenance_records").fetchone()[0] == 0
    db.close()


def test_provenance_append_rejects_naive_as_of_timestamp_without_writing():
    db = connect_database()
    with pytest.raises(ValidationError, match="timezone-aware"):
        ProvenanceRecordApplication().append(
            db, source_type="external_document", source_ref="document:abc",
            derivation_type="imported", as_of_at="2026-10-10T12:00:00", now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM provenance_records").fetchone()[0] == 0
    db.close()


def test_provenance_append_rejects_invalid_explicit_identifier_without_writing():
    db = connect_database()
    with pytest.raises(ValidationError, match="provenance_id must be a valid UUID"):
        ProvenanceRecordApplication().append(
            db, source_type="external_document", source_ref="document:abc",
            derivation_type="imported", provenance_id="not-a-uuid", now=STAMP,
        )
    assert db.execute("SELECT COUNT(*) FROM provenance_records").fetchone()[0] == 0
    db.close()


def test_provenance_get_rejects_unknown_record():
    db = connect_database()
    with pytest.raises(ValidationError, match="does not exist"):
        ProvenanceRecordApplication().get(db, uuid.uuid4())
    db.close()
