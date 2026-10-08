from dataclasses import dataclass
from agent_core.application import ActivityApplication, IdentityApplication
from agent_core.config import Settings
from agent_core.infrastructure import AuditLogger
from agent_core.integrity import DurableOperationAuthority, RuntimeIntegrityGate, SemanticAuthority


@dataclass(frozen=True)
class ApplicationRuntime:
    settings: Settings
    audit: AuditLogger
    identity: IdentityApplication
    activities: ActivityApplication
    semantic: SemanticAuthority
    operations: DurableOperationAuthority
    integrity: RuntimeIntegrityGate

    @classmethod
    def create(cls, settings=None):
        settings = settings or Settings.from_environment()
        semantic = SemanticAuthority()
        operations = DurableOperationAuthority(settings.operation_store_path)
        return cls(
            settings,
            AuditLogger(),
            IdentityApplication(),
            ActivityApplication(),
            semantic,
            operations,
            RuntimeIntegrityGate(semantic, operations),
        )
