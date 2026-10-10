import base64
import hashlib
import os
from pathlib import Path
import subprocess
import sys

import pytest

from agent_core.config import Settings
from agent_core.file_attachment_application import FileAttachmentApplication
from agent_core.file_attachment_content_application import (
    ContentIntegrityError,
    ContentRetrievalError,
    ContentStorageFailure,
    FileAttachmentContentApplication,
    PartialAttachmentCreationError,
)
from agent_core.file_attachment_filesystem_store import LocalFilesystemContentStore
from agent_core.persistence import connect_database


def create(app, db, content=b"persistent attachment bytes"):
    return app.create_attachment(
        db,
        content=content,
        file_name="report.bin",
        media_type="application/octet-stream",
        metadata={"origin": "filesystem-test"},
    )


def test_new_adapter_instance_retrieves_persisted_content(tmp_path):
    root = tmp_path / "attachment-content"
    db_path = tmp_path / "attachments.sqlite"
    content = b"persisted\x00payload\xff"

    db = connect_database(db_path)
    attachment = create(FileAttachmentContentApplication(LocalFilesystemContentStore(root)), db, content)
    db.close()

    restored_db = connect_database(db_path)
    restored = FileAttachmentApplication().get_attachment(restored_db, attachment.attachment_id)
    app = FileAttachmentContentApplication(LocalFilesystemContentStore(root))
    assert app.get_attachment_content(restored_db, restored.attachment_id) == content
    restored_db.close()


def test_content_survives_two_separate_processes(tmp_path):
    root = tmp_path / "attachment-content"
    db_path = tmp_path / "attachments.sqlite"
    content = b"separate-process payload\x00\xff"
    repo_root = Path(__file__).resolve().parents[1]
    env = os.environ.copy()
    source_path = str(repo_root / "src")
    env["PYTHONPATH"] = source_path + os.pathsep + env.get("PYTHONPATH", "")

    writer = """
import sys
from agent_core.file_attachment_content_application import FileAttachmentContentApplication
from agent_core.file_attachment_filesystem_store import LocalFilesystemContentStore
from agent_core.persistence import connect_database
db = connect_database(sys.argv[1])
item = FileAttachmentContentApplication(LocalFilesystemContentStore(sys.argv[2])).create_attachment(
    db, content=b"separate-process payload\\x00\\xff", file_name="process.bin",
    media_type="application/octet-stream")
print(item.attachment_id)
db.close()
"""
    write_result = subprocess.run(
        [sys.executable, "-c", writer, str(db_path), str(root)],
        cwd=repo_root, env=env, check=True, capture_output=True, text=True,
    )
    attachment_id = write_result.stdout.strip()
    assert attachment_id

    reader = """
import base64, sys
from agent_core.file_attachment_content_application import FileAttachmentContentApplication
from agent_core.file_attachment_filesystem_store import LocalFilesystemContentStore
from agent_core.persistence import connect_database
db = connect_database(sys.argv[1])
content = FileAttachmentContentApplication(LocalFilesystemContentStore(sys.argv[2])).get_attachment_content(db, sys.argv[3])
print(base64.b64encode(content).decode("ascii"))
db.close()
"""
    read_result = subprocess.run(
        [sys.executable, "-c", reader, str(db_path), str(root), attachment_id],
        cwd=repo_root, env=env, check=True, capture_output=True, text=True,
    )
    assert base64.b64decode(read_result.stdout.strip()) == content


def test_failed_write_does_not_register_metadata(tmp_path, monkeypatch):
    root = tmp_path / "attachment-content"
    db = connect_database()
    store = LocalFilesystemContentStore(root)

    def fail_open(*args, **kwargs):
        raise OSError("simulated filesystem write failure")

    monkeypatch.setattr(store, "_open_root", fail_open)
    app = FileAttachmentContentApplication(store)
    with pytest.raises(ContentStorageFailure):
        create(app, db)
    assert FileAttachmentApplication().list_attachments(db) == ()
    db.close()


def test_missing_content_is_a_retrieval_error(tmp_path):
    db = connect_database()
    app = FileAttachmentContentApplication(LocalFilesystemContentStore(tmp_path / "objects"))
    attachment = create(app, db)
    (tmp_path / "objects" / attachment.storage_ref.removeprefix("localfs://")).unlink()

    with pytest.raises(ContentRetrievalError):
        app.get_attachment_content(db, attachment.attachment_id)
    db.close()


def test_tampered_content_is_rejected_by_application_integrity_check(tmp_path):
    db = connect_database()
    root = tmp_path / "objects"
    store = LocalFilesystemContentStore(root)
    app = FileAttachmentContentApplication(store)
    attachment = create(app, db, b"original bytes")

    object_path = root / attachment.storage_ref.removeprefix("localfs://")
    object_path.write_bytes(b"tampered bytes")

    with pytest.raises(ContentIntegrityError, match="SHA-256 mismatch"):
        app.get_attachment_content(db, attachment.attachment_id)
    db.close()


@pytest.mark.parametrize(
    "reference",
    [
        "../outside",
        "localfs://../outside",
        "localfs:///etc/passwd",
        "file:///etc/passwd",
        "localfs://" + "A" * 32,
    ],
)
def test_untrusted_references_cannot_select_filesystem_paths(tmp_path, reference):
    store = LocalFilesystemContentStore(tmp_path / "objects")
    with pytest.raises(ValueError, match="storage reference"):
        store.get(reference)


def test_symlinked_object_is_rejected_without_reading_outside_file(tmp_path):
    root = tmp_path / "objects"
    store = LocalFilesystemContentStore(root)
    reference = store.put(b"safe bytes")
    object_path = root / reference.removeprefix("localfs://")
    object_path.unlink()
    outside = tmp_path / "outside.bin"
    outside.write_bytes(b"secret outside content")
    object_path.symlink_to(outside)

    with pytest.raises(OSError):
        store.get(reference)


def test_settings_reads_independent_attachment_store_path(monkeypatch):
    monkeypatch.setenv("AGENT_ATTACHMENT_CONTENT_STORE", "/var/lib/agent/attachments")
    settings = Settings.from_environment()
    assert settings.attachment_content_store_path == "/var/lib/agent/attachments"


def test_filesystem_adapter_does_not_duplicate_integrity_validation(tmp_path):
    root = tmp_path / "objects"
    store = LocalFilesystemContentStore(root)
    content = b"checksum belongs to application"
    reference = store.put(content)
    assert store.get(reference) == content
    assert hashlib.sha256(store.get(reference)).hexdigest() == hashlib.sha256(content).hexdigest()
