from dataclasses import dataclass
import os


@dataclass(frozen=True)
class Settings:
    environment: str = "development"
    log_level: str = "INFO"
    operation_store_path: str = ".agent_core/operations.sqlite3"
    attachment_content_store_path: str = ".agent_core/attachments"

    @classmethod
    def from_environment(cls):
        return cls(
            os.getenv("APP_ENV", "development"),
            os.getenv("APP_LOG_LEVEL", "INFO"),
            os.getenv("AGENT_OPERATION_STORE", ".agent_core/operations.sqlite3"),
            os.getenv("AGENT_ATTACHMENT_CONTENT_STORE", ".agent_core/attachments"),
        )
