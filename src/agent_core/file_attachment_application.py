"""Persisted metadata registry for files stored by an external storage adapter.

This module records an opaque storage reference and verified metadata supplied
by the caller. It does not store bytes, fetch content, authorize access, or
claim that a storage object currently exists.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime, timezone
from uuid import UUID, uuid4

from agent_core.shared import ValidationError


_SHA256 = re.compile(r"^[0-9a-f]{64}$")


@dataclass(frozen=True)
class FileAttachment:
    attachment_id: UUID
    storage_ref: str
    file_name: str
    media_type: str
    size_bytes: int
    checksum_sha256: str
    metadata: dict
    created_at: str


class FileAttachmentApplication:
    @staticmethod
    def _uuid(value, label):
        try:
            return UUID(str(value))
        except (ValueError, TypeError, AttributeError) as exc:
            raise ValidationError(f"{label} must be a valid UUID") from exc

    @staticmethod
    def _timestamp(value):
        if isinstance(value, str):
            try:
                value = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError as exc:
                raise ValidationError("created_at must be a valid ISO-8601 timestamp") from exc
        if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
            raise ValidationError("created_at must be timezone-aware")
        return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")

    @staticmethod
    def _record(row):
        attachment_id, storage_ref, file_name, media_type, size_bytes, checksum, metadata_json, created_at = row
        return FileAttachment(
            UUID(attachment_id), storage_ref, file_name, media_type, size_bytes,
            checksum, json.loads(metadata_json), created_at,
        )

    def create_attachment(
        self, connection, *, storage_ref, file_name, media_type, size_bytes,
        checksum_sha256, metadata=None, attachment_id=None, created_at=None,
    ) -> FileAttachment:
        if not isinstance(storage_ref, str) or not storage_ref.strip():
            raise ValidationError("storage_ref is required")
        if not isinstance(file_name, str) or not file_name.strip() or file_name.strip() in {".", ".."}:
            raise ValidationError("file_name is required")
        if "/" in file_name or "\\" in file_name or "\x00" in file_name:
            raise ValidationError("file_name must be a basename, not a path")
        if not isinstance(media_type, str) or not re.fullmatch(r"[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+", media_type.strip()):
            raise ValidationError("media_type must be a valid type/subtype")
        if isinstance(size_bytes, bool) or not isinstance(size_bytes, int) or size_bytes <= 0:
            raise ValidationError("size_bytes must be a positive integer")
        if not isinstance(checksum_sha256, str) or not _SHA256.fullmatch(checksum_sha256):
            raise ValidationError("checksum_sha256 must be 64 lowercase hexadecimal characters")
        if metadata is None:
            metadata = {}
        if not isinstance(metadata, dict) or not all(isinstance(k, str) for k in metadata):
            raise ValidationError("metadata must be a JSON object with string keys")
        try:
            metadata_json = json.dumps(metadata, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)
        except (TypeError, ValueError) as exc:
            raise ValidationError("metadata must contain JSON-compatible values") from exc

        key = str(self._uuid(attachment_id, "Attachment identifier")) if attachment_id is not None else str(uuid4())
        timestamp = self._timestamp(created_at or datetime.now(timezone.utc))
        values = (
            key, storage_ref.strip(), file_name.strip(), media_type.strip().lower(),
            size_bytes, checksum_sha256, metadata_json, timestamp,
        )
        try:
            with connection:
                connection.execute(
                    "INSERT INTO file_attachments(attachment_id,storage_ref,file_name,media_type,"
                    "size_bytes,checksum_sha256,metadata_json,created_at) VALUES(?,?,?,?,?,?,?,?)",
                    values,
                )
        except Exception as exc:
            # Convert uniqueness/constraint failures to the application's validation contract.
            import sqlite3
            if isinstance(exc, sqlite3.IntegrityError):
                raise ValidationError("attachment identifier or storage_ref already exists, or metadata violates the schema") from exc
            raise
        return self.get_attachment(connection, key)

    def get_attachment(self, connection, attachment_id) -> FileAttachment:
        key = str(self._uuid(attachment_id, "Attachment identifier"))
        row = connection.execute(
            "SELECT attachment_id,storage_ref,file_name,media_type,size_bytes,checksum_sha256,metadata_json,created_at "
            "FROM file_attachments WHERE attachment_id=?", (key,),
        ).fetchone()
        if row is None:
            raise ValidationError("Attachment does not exist")
        return self._record(row)

    def list_attachments(self, connection, *, limit=100) -> tuple[FileAttachment, ...]:
        if isinstance(limit, bool) or not isinstance(limit, int) or not 1 <= limit <= 1000:
            raise ValidationError("limit must be an integer between 1 and 1000")
        rows = connection.execute(
            "SELECT attachment_id,storage_ref,file_name,media_type,size_bytes,checksum_sha256,metadata_json,created_at "
            "FROM file_attachments ORDER BY created_at,attachment_id LIMIT ?", (limit,),
        ).fetchall()
        return tuple(self._record(row) for row in rows)
