from datetime import datetime, timezone
import hashlib
import json
import sqlite3
import pytest

from agent_core.file_attachment_application import FileAttachmentApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


def test_attachment_metadata_persists_and_restores_after_reopen(tmp_path):
    path = tmp_path / "attachments.sqlite"
    app = FileAttachmentApplication()
    digest = hashlib.sha256(b"file bytes supplied by storage adapter").hexdigest()
    db = connect_database(path)
    item = app.create_attachment(
        db, storage_ref="object://bucket/object-1", file_name="report.pdf",
        media_type="application/pdf", size_bytes=32, checksum_sha256=digest,
        metadata={"source": "user-upload", "labels": ["report", "2026"]},
        created_at=datetime(2026, 1, 2, tzinfo=timezone.utc),
    )
    db.close()

    reopened = connect_database(path)
    restored = app.get_attachment(reopened, item.attachment_id)
    assert restored == item
    assert restored.metadata == {"labels": ["report", "2026"], "source": "user-upload"}
    assert app.list_attachments(reopened) == (item,)
    reopened.close()


@pytest.mark.parametrize("kwargs", [
    {"storage_ref": "", "file_name": "x.txt", "media_type": "text/plain", "size_bytes": 1, "checksum_sha256": "a" * 64},
    {"storage_ref": "obj://1", "file_name": "../x.txt", "media_type": "text/plain", "size_bytes": 1, "checksum_sha256": "a" * 64},
    {"storage_ref": "obj://1", "file_name": "x.txt", "media_type": "not-a-type", "size_bytes": 1, "checksum_sha256": "a" * 64},
    {"storage_ref": "obj://1", "file_name": "x.txt", "media_type": "text/plain", "size_bytes": 0, "checksum_sha256": "a" * 64},
    {"storage_ref": "obj://1", "file_name": "x.txt", "media_type": "text/plain", "size_bytes": True, "checksum_sha256": "a" * 64},
    {"storage_ref": "obj://1", "file_name": "x.txt", "media_type": "text/plain", "size_bytes": 1, "checksum_sha256": "A" * 64},
    {"storage_ref": "obj://1", "file_name": "x.txt", "media_type": "text/plain", "size_bytes": 1, "checksum_sha256": "a" * 64, "metadata": ["not", "an", "object"]},
])
def test_rejects_invalid_attachment_metadata(kwargs):
    db = connect_database()
    with pytest.raises(ValidationError):
        FileAttachmentApplication().create_attachment(db, **kwargs)
    db.close()


def test_duplicate_storage_reference_fails_closed():
    db = connect_database()
    app = FileAttachmentApplication()
    common = dict(storage_ref="object://bucket/same", file_name="a.txt", media_type="text/plain",
                  size_bytes=1, checksum_sha256=hashlib.sha256(b"a").hexdigest())
    app.create_attachment(db, **common)
    with pytest.raises(ValidationError, match="already exists"):
        app.create_attachment(db, **{**common, "file_name": "b.txt"})
    db.close()


def test_missing_attachment_and_invalid_limit_are_rejected():
    db = connect_database()
    app = FileAttachmentApplication()
    with pytest.raises(ValidationError, match="does not exist"):
        app.get_attachment(db, "4e61d5ec-6c12-4e32-8ef0-0a8fbc09b7a2")
    for limit in (0, 1001, True, "10"):
        with pytest.raises(ValidationError):
            app.list_attachments(db, limit=limit)
    db.close()
