from dataclasses import dataclass
from agent_core.application import ActivityApplication,IdentityApplication
from agent_core.config import Settings
from agent_core.infrastructure import AuditLogger
@dataclass(frozen=True)
class ApplicationRuntime:
    settings:Settings; audit:AuditLogger; identity:IdentityApplication; activities:ActivityApplication
    @classmethod
    def create(cls,settings=None): return cls(settings or Settings.from_environment(),AuditLogger(),IdentityApplication(),ActivityApplication())
