"""Content storage boundary and integrity-checked attachment operations.

The ContentStore protocol is provider-neutral. InMemoryContentStore is a small,
replaceable adapter for tests and local integration; it is not durable storage.
"""
from __future__ import annotations

import hashlib
from typing import Protocol
from uuid import uuid4

from agent_core.file_attachment_application import FileAttachment, FileAttachmentApplication


class ContentStore(Protocol):
    """Opaque byte storage interface; references do not imply continued existence."""

    def put(self, content: bytes) -> str:
        """Store bytes and return an opaque reference."""
        ...

    def get(self, storage_ref: str) -> bytes:
        """Return bytes for a reference or raise if unavailable."""
        ...


class ContentStoreError(RuntimeError):
    """The content storage operation failed."""


class ContentStorageFailure(ContentStoreError):
    """The write failed before metadata registration was attempted."""

    storage_ref: str | None = None

    def __init__(self, message: str, *, storage_ref: str | None = None):
        super().__init__(message)
        self.storage_ref = storage_ref


class PartialAttachmentCreationError(RuntimeError):
    """Content was stored, but its metadata record was not saved."""

    def __init__(self, storage_ref: str, message: str = "Content stored but attachment metadata was not saved"):
        super().__init__(f"{message}; storage_ref={storage_ref}")
        self.storage_ref = storage_ref


class ContentRetrievalError(ContentStoreError):
    """Stored content could not be retrieved."""


class ContentIntegrityError(ContentRetrievalError):
    """Retrieved bytes do not match their persisted size or SHA-256."""


class InMemoryContentStore:
    """Replaceable, non-durable adapter for tests and local integration."""

    def __init__(self):
        self._objects: dict[str, bytes] = {}

    def put(self, content: bytes) -> str:
        if not isinstance(content, bytes):
            raise TypeError("content must be bytes")
        storage_ref = f"memory://{uuid4()}"
        self._objects[storage_ref] = content
        return storage_ref

    def get(self, storage_ref: str) -> bytes:
        try:
            return self._objects[storage_ref]
        except KeyError as exc:
            raise FileNotFoundError(f"content not found: {storage_ref}") from exc


class FileAttachmentContentApplication:
    """Coordinates byte storage and metadata registration without false success."""

    def __init__(
        self,
        content_store: ContentStore,
        metadata_application: FileAttachmentApplication | None = None,
    ):
        self._content_store = content_store
        self._metadata_application = metadata_application or FileAttachmentApplication()

    def create_attachment(
        self,
        connection,
        *,
        content: bytes,
        file_name: str,
        media_type: str,
        metadata: dict | None = None,
        attachment_id=None,
        created_at=None,
    ) -> FileAttachment:
        if not isinstance(content, bytes):
            raise ValueError("content must be bytes")
        if not content:
            # The existing metadata schema requires size_bytes > 0.
            raise ValueError("content must not be empty")

        size_bytes = len(content)
        checksum = hashlib.sha256(content).hexdigest()
        try:
            storage_ref = self._content_store.put(content)
            if not isinstance(storage_ref, str) or not storage_ref.strip():
                raise ValueError("content store returned an invalid storage reference")
            storage_ref = storage_ref.strip()
        except Exception as exc:
            raise ContentStorageFailure("Failed to store attachment content") from exc

        try:
            return self._metadata_application.create_attachment(
                connection,
                storage_ref=storage_ref,
                file_name=file_name,
                media_type=media_type,
                size_bytes=size_bytes,
                checksum_sha256=checksum,
                metadata=metadata,
                attachment_id=attachment_id,
                created_at=created_at,
            )
        except Exception as exc:
            # Intentionally do not delete the stored bytes or retry automatically.
            raise PartialAttachmentCreationError(storage_ref) from exc

    def get_attachment_content(self, connection, attachment_id) -> bytes:
        attachment = self._metadata_application.get_attachment(connection, attachment_id)
        try:
            content = self._content_store.get(attachment.storage_ref)
        except Exception as exc:
            raise ContentRetrievalError(
                f"Failed to retrieve attachment content; storage_ref={attachment.storage_ref}"
            ) from exc

        if not isinstance(content, bytes):
            raise ContentRetrievalError(
                f"Content store returned a non-bytes value; storage_ref={attachment.storage_ref}"
            )
        if len(content) != attachment.size_bytes:
            raise ContentIntegrityError(
                f"Attachment size mismatch; expected={attachment.size_bytes}, actual={len(content)}, "
                f"storage_ref={attachment.storage_ref}"
            )
        actual_checksum = hashlib.sha256(content).hexdigest()
        if actual_checksum != attachment.checksum_sha256:
            raise ContentIntegrityError(
                f"Attachment SHA-256 mismatch; storage_ref={attachment.storage_ref}"
            )
        return content
