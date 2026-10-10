import hashlib
import sqlite3

import pytest

from agent_core.file_attachment_application import FileAttachmentApplication
from agent_core.file_attachment_content_application import (
    ContentIntegrityError,
    ContentRetrievalError,
    ContentStorageFailure,
    FileAttachmentContentApplication,
    InMemoryContentStore,
    PartialAttachmentCreationError,
)
from agent_core.persistence import connect_database


class FailingPutStore:
    def put(self, content):
        raise OSError("write unavailable")

    def get(self, storage_ref):
        raise AssertionError("get should not be called")


class FailingMetadataConnection:
    """Connection proxy that fails only the metadata INSERT."""

    def __init__(self, connection):
        self.connection = connection

    def __enter__(self):
        self.connection.__enter__()
        return self

    def __exit__(self, exc_type, exc, tb):
        return self.connection.__exit__(exc_type, exc, tb)

    def execute(self, sql, parameters=()):
        if sql.lstrip().upper().startswith("INSERT INTO FILE_ATTACHMENTS"):
            raise sqlite3.OperationalError("simulated metadata write failure")
        return self.connection.execute(sql, parameters)


def create(app, db, content=b"content bytes"):
    return app.create_attachment(
        db, content=content, file_name="report.bin", media_type="application/octet-stream",
        metadata={"origin": "test"},
    )


def test_content_round_trip_uses_computed_size_and_sha256():
    db = connect_database()
    store = InMemoryContentStore()
    app = FileAttachmentContentApplication(store)
    content = b"binary\x00payload\xff"

    attachment = create(app, db, content)

    assert attachment.size_bytes == len(content)
    assert attachment.checksum_sha256 == hashlib.sha256(content).hexdigest()
    assert app.get_attachment_content(db, attachment.attachment_id) == content
    db.close()


def test_content_and_metadata_survive_database_reopen_with_same_store(tmp_path):
    path = tmp_path / "attachments.sqlite"
    store = InMemoryContentStore()
    app = FileAttachmentContentApplication(store)
    db = connect_database(path)
    content = b"persistent metadata with adapter-held bytes"
    attachment = create(app, db, content)
    db.close()

    reopened = connect_database(path)
    restored = FileAttachmentApplication().get_attachment(reopened, attachment.attachment_id)
    assert restored == attachment
    assert app.get_attachment_content(reopened, attachment.attachment_id) == content
    reopened.close()


def test_content_write_failure_does_not_create_metadata():
    db = connect_database()
    app = FileAttachmentContentApplication(FailingPutStore())

    with pytest.raises(ContentStorageFailure) as error:
        create(app, db)

    assert error.value.storage_ref is None
    assert FileAttachmentApplication().list_attachments(db) == ()
    db.close()


def test_metadata_write_failure_is_explicit_partial_failure_and_keeps_content():
    db = connect_database()
    store = InMemoryContentStore()
    app = FileAttachmentContentApplication(store)
    failing_connection = FailingMetadataConnection(db)

    with pytest.raises(PartialAttachmentCreationError) as error:
        create(app, failing_connection, b"stored but unregistered")

    storage_ref = error.value.storage_ref
    assert storage_ref.startswith("memory://")
    assert store.get(storage_ref) == b"stored but unregistered"
    assert FileAttachmentApplication().list_attachments(db) == ()
    db.close()


def test_missing_content_is_retrieval_failure_not_success():
    db = connect_database()
    store = InMemoryContentStore()
    app = FileAttachmentContentApplication(store)
    attachment = create(app, db)
    store._objects.pop(attachment.storage_ref)

    with pytest.raises(ContentRetrievalError, match="Failed to retrieve"):
        app.get_attachment_content(db, attachment.attachment_id)
    db.close()


@pytest.mark.parametrize(
    ("replacement", "message"),
    [
        (b"short", "size mismatch"),
        (b"tampered content", "SHA-256 mismatch"),
    ],
)
def test_integrity_mismatch_is_rejected_before_return(replacement, message):
    db = connect_database()
    store = InMemoryContentStore()
    app = FileAttachmentContentApplication(store)
    attachment = create(app, db, b"original content")
    store._objects[attachment.storage_ref] = replacement

    with pytest.raises(ContentIntegrityError, match=message):
        app.get_attachment_content(db, attachment.attachment_id)
    db.close()


def test_invalid_or_empty_content_is_rejected_before_storage():
    db = connect_database()
    store = InMemoryContentStore()
    app = FileAttachmentContentApplication(store)

    for content in (b"", "not bytes", bytearray(b"x")):
        with pytest.raises(ValueError, match="content"):
            create(app, db, content)
    assert store._objects == {}
    assert FileAttachmentApplication().list_attachments(db) == ()
    db.close()


def test_metadata_registry_existing_contract_remains_compatible():
    db = connect_database()
    metadata_app = FileAttachmentApplication()
    digest = hashlib.sha256(b"legacy").hexdigest()
    item = metadata_app.create_attachment(
        db, storage_ref="opaque://legacy", file_name="legacy.txt", media_type="text/plain",
        size_bytes=6, checksum_sha256=digest,
    )
    assert metadata_app.get_attachment(db, item.attachment_id) == item
    assert metadata_app.list_attachments(db) == (item,)
    db.close()
