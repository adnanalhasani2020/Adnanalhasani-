# Hospital operations core

An isolated hospital-domain development foundation that does not alter existing commerce/service modules.

## Included modules
- SQLite schema with constraints for patients, doctors, appointments, encounters, wards/beds, admissions, laboratory orders, medicines, prescriptions, invoices, payments, and audit events.
- Transactional service workflows for patient/doctor registration, appointment overlap prevention, admission/discharge, invoicing/payments, encounter lifecycle, lab status/results, and pharmacy dispensing with atomic stock deduction.
- Token-protected JSON API for dashboard counts and creating patients, doctors, appointments, lab orders, and medicines.
- Regression tests for business rules and API authentication/validation.
- GitHub Actions workflow dedicated to hospital regression tests.

## Quick start

Requires Python 3.11+ and no third-party runtime dependencies.

```bash
python -m unittest discover -s hospital/tests -v
```

Run the development API locally (use a strong random token; the example is only a placeholder):

```bash
export HOSPITAL_API_TOKEN="$(python -c 'import secrets; print(secrets.token_urlsafe(48))')"
export HOSPITAL_DB_PATH="hospital.sqlite3"
python -m hospital.api
```

The API binds to `127.0.0.1:8080` by default. Requests require `Authorization: Bearer <token>`. Available routes:
- `GET /health`
- `GET /api/dashboard`
- `POST /api/patients`
- `POST /api/doctors`
- `POST /api/appointments`
- `POST /api/lab-orders`
- `POST /api/medicines`

## Important safety limits
This is a development foundation, **not a production-ready or clinically validated hospital information system**. The bearer token is a single shared secret, not per-user authentication; roles in the schema are not yet enforced by API authorization middleware. Do not expose the development server directly to the internet or use real patient data. Before production, add per-user authentication and role-based access control, TLS/reverse proxy, secure secret management, encryption/key management, backup/restore testing, migrations, monitoring, retention policies, audit review, clinical workflow validation, and applicable regulatory review.

Use ISO-8601 timestamps consistently (prefer UTC or a documented hospital timezone). Currency amounts are integer minor units; never use floating-point amounts. Test fixtures must not contain real patient data.
