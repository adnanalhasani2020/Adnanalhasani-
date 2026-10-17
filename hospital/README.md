# Hospital operations core

An isolated hospital-domain development foundation that does not alter existing commerce/service modules.

## Included modules
- SQLite schema with constraints for patients, doctors, appointments, encounters, wards/beds, admissions, laboratory orders, medicines, prescriptions, invoices, payments, and audit events.
- Transactional workflows for registration, appointment overlap prevention, admission/discharge, invoices/payments, encounter lifecycle, lab status/results, and pharmacy dispensing with atomic stock deduction.
- Token-protected JSON API and Arabic right-to-left development UI for dashboard metrics, patient/doctor registration, appointments, laboratory orders, medicines, prescriptions, admissions, invoices, and payments.
- API endpoints for encounter lifecycle, laboratory result updates, pharmacy prescriptions/dispensing, beds, and admissions/discharge.
- Regression tests for business rules, API authentication/validation, and clinical workflows.
- GitHub Actions workflow dedicated to hospital regression tests.

## Quick start

Requires Python 3.11+ and no third-party runtime dependencies.

```bash
python -m unittest discover -s hospital/tests -v
```

Run the development UI/API locally:

```bash
export HOSPITAL_API_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(48))')"
export HOSPITAL_DB_PATH="hospital.sqlite3"
python -m hospital.api
```

Open `http://127.0.0.1:8080`. The Arabic interface uses the same origin as the API. Enter the shared token in the UI to call authenticated endpoints. Keep the token private and use a separate random token for each development environment. The API binds to `127.0.0.1` by default and rejects non-loopback bind hosts unless `HOSPITAL_API_ALLOW_REMOTE=1` is explicitly set. This override is dangerous with the shared-token development API: only use it behind a properly configured trusted reverse proxy/network, never expose the development server directly to the internet.

### Database backup and restore

The database contains sensitive clinical and financial records. Keep backups access-restricted and outside public/web-served directories. The examples below assume the service uses `hospital.sqlite3` in the current directory and that the `sqlite3` CLI is installed.

Create a consistent SQLite backup (this works while the service is running):

```bash
umask 077
sqlite3 hospital.sqlite3 ".backup 'hospital-backup-$(date +%Y%m%d-%H%M%S).sqlite3'"
```

Check a backup before relying on it:

```bash
sqlite3 hospital-backup-YYYYMMDD-HHMMSS.sqlite3 'PRAGMA integrity_check;'
```

The expected result is `ok`. Test restore procedures regularly in a separate, non-production directory. To restore, first stop the API, preserve the current database, then replace it with a verified backup; do not overwrite a live database:

```bash
cp -p hospital.sqlite3 hospital.sqlite3.before-restore
cp -p hospital-backup-YYYYMMDD-HHMMSS.sqlite3 hospital.sqlite3
chmod 600 hospital.sqlite3
sqlite3 hospital.sqlite3 'PRAGMA integrity_check;'
```

Use your actual backup filename in place of the example. Store a second encrypted copy in an access-controlled location if required by your recovery plan; never commit database files or backups to Git.

### API routes
- `GET /health`, `GET /api/dashboard`
- `GET /api/patients`, `/api/doctors`, `/api/appointments`, `/api/lab-orders`, `/api/medicines`, `/api/prescriptions`, `/api/beds`, `/api/admissions`, `/api/invoices` (authenticated, searchable with `?search=`, paginated with `?limit=50&offset=0`; maximum page size 100)
- `POST /api/patients`, `POST /api/doctors`, `POST /api/appointments`
- `POST /api/encounters`, `POST /api/encounters/{id}/close`
- `POST /api/lab-orders`, `POST /api/lab-orders/{id}/status`
- `POST /api/medicines`, `POST /api/prescriptions`, `POST /api/prescriptions/{id}/dispense`
- `POST /api/beds`, `POST /api/admissions`, `POST /api/admissions/{id}/discharge`
- `POST /api/invoices`, `POST /api/payments` (amounts use integer minor units; payments cannot exceed the invoice balance)
- `GET /api/diagnoses`, `POST /api/diagnoses` (diagnoses are linked to a clinical encounter; a final diagnosis also updates the encounter summary)
- `GET /api/allergies`, `POST /api/allergies` (patient-level allergy register with severity and active/inactive status)
- `GET /api/vitals`, `POST /api/vitals` (encounter-linked observations; plausible numeric ranges are enforced by SQLite constraints)
- `GET /api/nursing-notes`, `POST /api/nursing-notes` (encounter-linked observations, care plans, handovers, medication notes, and incidents)
- `POST /api/admissions` accepts optional `encounter_id`; when supplied, it must belong to the same patient.
- `GET /api/inventory-movements`, `POST /api/inventory-adjustments` (signed stock adjustments are audited and cannot make stock negative)
- `GET /api/suppliers`, `POST /api/suppliers`; `GET /api/purchase-orders`, `POST /api/purchase-orders`, `POST /api/purchase-orders/{id}/receive` (receipt updates stock and writes an inventory ledger entry atomically)
- `GET /api/reports` (aggregate clinical, bed/inventory/procurement, and financial totals; no patient-level data)

## Per-user authentication mode (development preview)

The hospital API now includes an opt-in per-user authentication path and role checks. This is a development preview; it does not make the system production-ready.

Create the first administrator interactively (the command refuses to run when an authentication user already exists):

```bash
export HOSPITAL_DB_PATH="hospital.sqlite3"
python -m hospital.bootstrap_admin
```

Then start the API in per-user mode:

```bash
export HOSPITAL_AUTH_MODE="users"
export HOSPITAL_DB_PATH="hospital.sqlite3"
python -m hospital.api
```

The login endpoint is `POST /api/auth/login` with a JSON body containing `username` and `password`. Use the returned bearer session token for authenticated API requests. `GET /api/auth/me` reports the current account, `POST /api/auth/logout` revokes its session, and administrators can create accounts with `POST /api/users`, list accounts with `GET /api/users`, and activate/deactivate accounts through `POST /api/users/{id}/active` with boolean `active` and timestamp `at` fields.

Passwords are stored as PBKDF2-HMAC-SHA256 hashes with per-user random salts. Session tokens are stored as SHA-256 hashes and expire after eight hours. The role matrix is deliberately least-privilege and should be reviewed against actual hospital duties before use. The legacy shared-token mode remains the default for backward compatibility; it does not enforce per-user roles. Set `HOSPITAL_AUTH_MODE=users` explicitly to exercise per-user authentication.

Keep the service bound to localhost. Do not expose it to the internet, and do not use real patient data. Production still requires TLS/reverse proxy configuration, secure secret and key management, clinical validation, security review, monitoring, tested backup/restore, and applicable regulatory review.

## Unified clinical workflow and operational ledger

Visits are represented by encounters. Diagnoses, vital signs, and nursing notes reference an encounter; allergies reference the patient; lab orders and prescriptions already support encounter IDs; admissions now support an optional encounter link with patient consistency validation. Stock adjustments, dispensing, and purchase receipts are recorded in an inventory movement ledger. Purchase receipts can be applied only once, and stock updates and ledger writes are transactional. `GET /api/reports` returns aggregate counts and integer-minor-unit financial totals for the admin dashboard.

The schema initialization includes an additive migration for the admission-to-encounter link. Back up test databases before trying new schema revisions. These workflows are a software foundation only; vital-sign range checks are data-quality guardrails, not clinical interpretation or decision support.

## Important safety limits
This is a development foundation, **not a production-ready or clinically validated hospital information system**. Per-user authentication and role checks are available as an opt-in development preview (`HOSPITAL_AUTH_MODE=users`); the legacy shared-token mode remains the default and does not enforce per-user roles. The role matrix and route coverage still require independent security review before real-world use. Do not expose the development server directly to the internet or use real patient data. Before production, complete threat modeling and authorization review, configure TLS/reverse proxy, secure secret management and encryption/key management, test backup/restore, add migrations and monitoring, define retention policies, audit access, validate clinical workflows, and complete applicable regulatory review.

Use ISO-8601 timestamps consistently (prefer UTC or a documented hospital timezone). Currency amounts are integer minor units; never use floating-point amounts. Test fixtures must not contain real patient data.
