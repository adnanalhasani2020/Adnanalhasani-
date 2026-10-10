"""Durable local-filesystem adapter for the attachment ContentStore contract.

References are generated opaque identifiers, never filesystem paths. Integrity
validation remains the responsibility of FileAttachmentContentApplication.
"""
from __future__ import annotations

import errno
import os
import re
import stat
from pathlib import Path
from uuid import uuid4

from agent_core.file_attachment_content_application import ContentStore


_REFERENCE_PREFIX = "localfs://"
_REFERENCE_PATTERN = re.compile(r"localfs://([0-9a-f]{32})\\Z")


class LocalFilesystemContentStore:
    """Store opaque content objects under a configured local directory.

    Writes use a same-directory temporary file followed by atomic replacement.
    The configured directory must be on a filesystem that supports atomic rename
    for the desired publication guarantee. This adapter does not provide backups,
    replication, retention, or cross-host sharing.
    """

    def __init__(self, root: str | os.PathLike[str] = ".agent_core/attachments"):
        configured_root = Path(root).expanduser()
        configured_root.mkdir(parents=True, exist_ok=True)
        self._root = configured_root.resolve(strict=True)
        if not self._root.is_dir():
            raise NotADirectoryError(f"attachment content root is not a directory: {self._root}")

    def _open_root(self) -> int:
        flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
        root_fd = os.open(self._root, flags)
        if not stat.S_ISDIR(os.fstat(root_fd).st_mode):
            os.close(root_fd)
            raise NotADirectoryError(f"attachment content root is not a directory: {self._root}")
        return root_fd

    @staticmethod
    def _reference_name(storage_ref: str) -> str:
        if not isinstance(storage_ref, str):
            raise ValueError("invalid local attachment storage reference")
        match = _REFERENCE_PATTERN.fullmatch(storage_ref)
        if match is None:
            raise ValueError("invalid local attachment storage reference")
        return match.group(1)

    @staticmethod
    def _sync_directory(root_fd: int) -> None:
        try:
            os.fsync(root_fd)
        except OSError as exc:
            # Some filesystems/platforms do not support syncing directory handles.
            if exc.errno not in {errno.EINVAL, errno.ENOTSUP, getattr(errno, "EOPNOTSUPP", errno.ENOTSUP)}:
                raise

    def put(self, content: bytes) -> str:
        if not isinstance(content, bytes):
            raise TypeError("content must be bytes")

        root_fd = self._open_root()
        temporary_name = f".tmp-{uuid4().hex}"
        try:
            storage_ref = f"{_REFERENCE_PREFIX}{uuid4().hex}"
            final_name = self._reference_name(storage_ref)
            flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
            content_fd = os.open(temporary_name, flags, 0o600, dir_fd=root_fd)
            try:
                with os.fdopen(content_fd, "wb") as output:
                    output.write(content)
                    output.flush()
                    os.fsync(output.fileno())
            except BaseException:
                # Remove only an unpublished temporary file, never a stored object.
                try:
                    os.unlink(temporary_name, dir_fd=root_fd)
                except FileNotFoundError:
                    pass
                raise

            try:
                os.replace(temporary_name, final_name, src_dir_fd=root_fd, dst_dir_fd=root_fd)
            except BaseException:
                # A failed publication leaves no completed object under its reference.
                try:
                    os.unlink(temporary_name, dir_fd=root_fd)
                except FileNotFoundError:
                    pass
                raise
            self._sync_directory(root_fd)
            return storage_ref
        finally:
            os.close(root_fd)

    def get(self, storage_ref: str) -> bytes:
        object_name = self._reference_name(storage_ref)
        root_fd = self._open_root()
        try:
            flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
            content_fd = os.open(object_name, flags, dir_fd=root_fd)
            try:
                if not stat.S_ISREG(os.fstat(content_fd).st_mode):
                    raise OSError("attachment content object is not a regular file")
                with os.fdopen(content_fd, "rb") as source:
                    return source.read()
            except BaseException:
                # fdopen owns and closes content_fd after successful construction.
                try:
                    os.close(content_fd)
                except OSError:
                    pass
                raise
        finally:
            os.close(root_fd)
