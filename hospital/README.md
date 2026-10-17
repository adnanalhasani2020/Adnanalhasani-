# Hospital operations core

An isolated hospital-domain development foundation that does not alter existing commerce/service modules.

## Included modules
- SQLite schema with constraints for patients, doctors, appointments, encounters, wards/beds, admissions, laboratory orders, medicines, prescriptions, invoices, payments, and audit events.
- Transactional workflows for registration, appointment overlap prevention, admission/discharge, invoices/payments, encounter lifecycle, lab status/results, and pharmacy dispensing with atomic stock deduction.
- Token-protected JSON API and Arabic right-to-left development UI for dashboard metrics, patient/doctor registration, and appointment booking.
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

Open `http://127.0.0.1:8080`. The Arabic interface uses the same origin as the API. Enter the shared token in the UI to call authenticated endpoints.

### API routes
- `GET /health`, `GET /api/dashboard`
- `POST /api/patients`, `POST /api/doctors`, `POST /api/appointments`
- `POST /api/encounters`, `POST /api/encounters/{id}/close`
- `POST /api/lab-orders`, `POST /api/lab-orders/{id}/status`
- `POST /api/medicines`, `POST /api/prescriptions`, `POST /api/prescriptions/{id}/dispense`
- `POST /api/beds`, `POST /api/admissions`, `POST /api/admissions/{id}/discharge`
- `POST /api/invoices`, `POST /api/payments` (amounts use integer minor units; payments cannot exceed the invoice balance)

## Important safety limits
This is a development foundation, **not a production-ready or clinically validated hospital information system**. The bearer token is a single shared secret, not per-user authentication; roles in the schema are not yet enforced by API authorization middleware. Do not expose the development server directly to the internet or use real patient data. Before production, add per-user authentication and role-based access control, TLS/reverse proxy, secure secret management, encryption/key management, backup/restore testing, migrations, monitoring, retention policies, audit review, clinical workflow validation, and applicable regulatory review.

Use ISO-8601 timestamps consistently (prefer UTC or a documented hospital timezone). Currency amounts are integer minor units; never use floating-point amounts. Test fixtures must not contain real patient data.
