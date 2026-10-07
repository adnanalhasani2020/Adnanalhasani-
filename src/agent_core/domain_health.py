from dataclasses import dataclass, field
from enum import Enum
from uuid import UUID
from agent_core.shared import ValidationError, new_id

class PatientContextState(str,Enum): ACTIVE="active"; ENDED="ended"
class EncounterState(str,Enum): PLANNED="planned"; IN_PROGRESS="in-progress"; COMPLETED="completed"; CANCELLED="cancelled"
class ClinicalRecordState(str,Enum): DRAFT="draft"; RECORDED="recorded"; AMENDED="amended"; CORRECTED="corrected"; INVALIDATED="invalidated"
class ResultReportState(str,Enum): PRELIMINARY="preliminary"; FINAL="final"; AMENDED="amended"; CORRECTED="corrected"
class PrescriptionState(str,Enum): DRAFT="draft"; ISSUED="issued"; ACTIVE="active"; AMENDED="amended"; CANCELLED="cancelled"; EXPIRED="expired"; FULFILLED="fulfilled"

@dataclass
class PatientContext:
    person_id: UUID; id: UUID=field(default_factory=new_id); state: PatientContextState=PatientContextState.ACTIVE
    def __post_init__(self):
        if not isinstance(self.person_id,UUID): raise ValidationError("PatientContext must reference Person")
    def end(self): self.state=PatientContextState.ENDED

@dataclass
class Encounter:
    patient_context_id: UUID; service_id: UUID|None=None; activity_id: UUID|None=None
    id: UUID=field(default_factory=new_id); state: EncounterState=EncounterState.PLANNED
    def __post_init__(self):
        if not isinstance(self.patient_context_id,UUID): raise ValidationError("Encounter must reference Patient Context")
        if self.service_id is not None and not isinstance(self.service_id,UUID): raise ValidationError("Encounter.service_id must be UUID")
        if self.activity_id is not None and not isinstance(self.activity_id,UUID): raise ValidationError("Encounter.activity_id must be UUID")
    def start(self): self.state=EncounterState.IN_PROGRESS
    def complete(self): self.state=EncounterState.COMPLETED
    def cancel(self): self.state=EncounterState.CANCELLED

@dataclass
class ClinicalRecord:
    patient_context_id: UUID; content: str; encounter_id: UUID|None=None
    id: UUID=field(default_factory=new_id); state: ClinicalRecordState=ClinicalRecordState.DRAFT
    def __post_init__(self):
        if not isinstance(self.patient_context_id,UUID): raise ValidationError("ClinicalRecord must reference Patient Context")
        if not isinstance(self.content,str) or not self.content.strip(): raise ValidationError("ClinicalRecord.content is required")
        if self.encounter_id is not None and not isinstance(self.encounter_id,UUID): raise ValidationError("ClinicalRecord.encounter_id must be UUID")
    def record(self): self.state=ClinicalRecordState.RECORDED
    def amend(self): self.state=ClinicalRecordState.AMENDED
    def correct(self): self.state=ClinicalRecordState.CORRECTED
    def invalidate(self): self.state=ClinicalRecordState.INVALIDATED

@dataclass
class ResultReport:
    patient_context_id: UUID; content: str; encounter_id: UUID|None=None
    id: UUID=field(default_factory=new_id); state: ResultReportState=ResultReportState.PRELIMINARY
    def __post_init__(self):
        if not isinstance(self.patient_context_id,UUID): raise ValidationError("ResultReport must reference Patient Context")
        if not isinstance(self.content,str) or not self.content.strip(): raise ValidationError("ResultReport.content is required")
        if self.encounter_id is not None and not isinstance(self.encounter_id,UUID): raise ValidationError("ResultReport.encounter_id must be UUID")
    def finalize(self): self.state=ResultReportState.FINAL
    def amend(self): self.state=ResultReportState.AMENDED
    def correct(self): self.state=ResultReportState.CORRECTED

@dataclass
class Prescription:
    patient_context_id: UUID; instruction: str; encounter_id: UUID|None=None
    id: UUID=field(default_factory=new_id); state: PrescriptionState=PrescriptionState.DRAFT
    def __post_init__(self):
        if not isinstance(self.patient_context_id,UUID): raise ValidationError("Prescription must reference Patient Context")
        if not isinstance(self.instruction,str) or not self.instruction.strip(): raise ValidationError("Prescription.instruction is required")
        if self.encounter_id is not None and not isinstance(self.encounter_id,UUID): raise ValidationError("Prescription.encounter_id must be UUID")
    def issue(self): self.state=PrescriptionState.ISSUED
    def activate(self): self.state=PrescriptionState.ACTIVE
    def amend(self): self.state=PrescriptionState.AMENDED
    def cancel(self): self.state=PrescriptionState.CANCELLED
    def expire(self): self.state=PrescriptionState.EXPIRED
    def fulfill(self): self.state=PrescriptionState.FULFILLED
