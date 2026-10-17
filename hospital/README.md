# Hospital operations core (isolated foundation)

This directory introduces an isolated hospital-domain foundation without changing the existing commerce/services modules.

## Included
- SQLite schema with foreign keys and constraints for users/roles, patients, doctors, appointments, encounters, wards/beds, admissions, laboratory orders, medicine inventory, prescriptions, invoices, payments, and audit events.
- Python service layer for patient/doctor registration, appointment booking with overlap checks, admissions/discharge with bed occupancy checks, invoices and bounded payments.
- Operational dashboard counts that avoid returning patient-identifying details.
- Standard-library regression tests.

## Quick start
Requires Python 3.10+ and no third-party dependencies.

```bash
python -m unittest discover -s hospital/tests -v
python - <<'PY'
from hospital.core import HospitalDB
db = HospitalDB("hospital.sqlite3")
print(db.dashboard())
PY
```

## Important limitations
This is a development foundation, **not a production-ready or clinically validated hospital information system**. It currently has no web UI, HTTP API, authentication/session enforcement, permission middleware, encryption/key management, backups, migrations framework, full clinical workflows, pharmacy dispensing transaction service, lab-result authorization, or regulatory validation. The `users` table documents role vocabulary but does not authenticate users or enforce authorization. Integrate these controls before any real patient data is used.

Use ISO-8601 timestamps consistently (prefer UTC or a documented hospital timezone). Currency amounts are integer minor units; never use floating-point amounts. Do not store real patient data in test fixtures.
