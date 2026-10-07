import logging
from dataclasses import dataclass
from datetime import datetime,timezone
from typing import Any,Mapping
@dataclass(frozen=True)
class AuditEvent: action:str; subject_type:str; subject_id:str; occurred_at:str; attributes:Mapping[str,Any]
class AuditLogger:
    def __init__(self,logger=None): self._logger=logger or logging.getLogger("agent_core.audit")
    def record(self,action,subject_type,subject_id,**attributes):
        e=AuditEvent(action,subject_type,subject_id,datetime.now(timezone.utc).isoformat(),attributes); self._logger.info("audit",extra={"audit_event":e}); return e
