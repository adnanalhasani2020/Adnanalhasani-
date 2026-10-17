"""Small authenticated JSON API for local development.

Set HOSPITAL_API_TOKEN to a long random secret before starting. Do not expose this
development server directly to the public internet; deploy behind TLS, a hardened
reverse proxy, real per-user authentication/authorization, and operational controls.
"""
from __future__ import annotations

import hmac
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from .core import HospitalDB, HospitalError
from .workflows import add_medicine, order_lab_test


def make_handler(database: HospitalDB, api_token: str):
    if not api_token or len(api_token) < 32:
        raise ValueError("HOSPITAL_API_TOKEN must be set to a secret of at least 32 characters")

    class Handler(BaseHTTPRequestHandler):
        server_version = "HospitalDevAPI/0.1"

        def log_message(self, format: str, *args: Any) -> None:
            # Avoid default access logs containing query strings or accidental PHI.
            return

        def _send(self, status: int, payload: dict[str, Any]) -> None:
            raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(raw)

        def _authorized(self) -> bool:
            supplied = self.headers.get("Authorization", "")
            prefix = "Bearer "
            candidate = supplied[len(prefix):] if supplied.startswith(prefix) else ""
            if not hmac.compare_digest(candidate, api_token):
                self._send(401, {"error": "unauthorized"})
                return False
            return True

        def _body(self) -> dict[str, Any]:
            length = int(self.headers.get("Content-Length", "0"))
            if length < 1 or length > 65536:
                raise HospitalError("request body must be between 1 and 65536 bytes")
            try:
                data = json.loads(self.rfile.read(length))
            except (json.JSONDecodeError, UnicodeDecodeError) as exc:
                raise HospitalError("request body must be valid JSON") from exc
            if not isinstance(data, dict):
                raise HospitalError("request body must be a JSON object")
            return data

        def _required(self, data: dict[str, Any], *fields: str) -> None:
            missing = [name for name in fields if name not in data or data[name] is None]
            if missing:
                raise HospitalError("missing required fields: " + ", ".join(missing))

        def do_GET(self) -> None:
            if not self._authorized():
                return
            if self.path == "/health":
                self._send(200, {"status": "ok", "service": "hospital-api"})
            elif self.path == "/api/dashboard":
                self._send(200, {"data": database.dashboard()})
            else:
                self._send(404, {"error": "not_found"})

        def do_POST(self) -> None:
            if not self._authorized():
                return
            try:
                data = self._body()
                if self.path == "/api/patients":
                    self._required(data, "patient_no", "full_name", "created_at")
                    ident = database.register_patient(
                        patient_no=str(data["patient_no"]), full_name=str(data["full_name"]),
                        created_at=str(data["created_at"]), actor="api",
                        date_of_birth=data.get("date_of_birth"), sex=data.get("sex"),
                        phone=data.get("phone"), address=data.get("address"),
                        emergency_contact=data.get("emergency_contact"),
                        allergies=str(data.get("allergies", "")), notes=str(data.get("notes", "")))
                    self._send(201, {"id": ident, "status": "created"})
                elif self.path == "/api/doctors":
                    self._required(data, "employee_no", "full_name", "specialty", "at")
                    ident = database.register_doctor(
                        employee_no=str(data["employee_no"]), full_name=str(data["full_name"]),
                        specialty=str(data["specialty"]), at=str(data["at"]),
                        actor="api", phone=data.get("phone"))
                    self._send(201, {"id": ident, "status": "created"})
                elif self.path == "/api/appointments":
                    self._required(data, "patient_id", "doctor_id", "starts_at", "ends_at", "created_at")
                    ident = database.book_appointment(
                        patient_id=int(data["patient_id"]), doctor_id=int(data["doctor_id"]),
                        starts_at=str(data["starts_at"]), ends_at=str(data["ends_at"]),
                        created_at=str(data["created_at"]), actor="api",
                        reason=str(data.get("reason", "")))
                    self._send(201, {"id": ident, "status": "scheduled"})
                elif self.path == "/api/lab-orders":
                    self._required(data, "patient_id", "test_name", "ordered_at")
                    ident = order_lab_test(database, patient_id=int(data["patient_id"]),
                        test_name=str(data["test_name"]), ordered_at=str(data["ordered_at"]),
                        actor="api", doctor_id=data.get("doctor_id"),
                        encounter_id=data.get("encounter_id"))
                    self._send(201, {"id": ident, "status": "ordered"})
                elif self.path == "/api/medicines":
                    self._required(data, "sku", "name", "quantity", "reorder_level", "at")
                    ident = add_medicine(database, sku=str(data["sku"]), name=str(data["name"]),
                        quantity=int(data["quantity"]), reorder_level=int(data["reorder_level"]),
                        actor="api", at=str(data["at"]), unit=str(data.get("unit", "unit")))
                    self._send(201, {"id": ident, "status": "created"})
                else:
                    self._send(404, {"error": "not_found"})
            except HospitalError as exc:
                self._send(400, {"error": str(exc)})
            except (TypeError, ValueError, OverflowError):
                self._send(400, {"error": "invalid_field_value"})
            except Exception:
                # Never return exception text or database details to API clients.
                self._send(500, {"error": "internal_server_error"})

    return Handler


def serve() -> None:
    token = os.environ.get("HOSPITAL_API_TOKEN", "")
    db_path = os.environ.get("HOSPITAL_DB_PATH", "hospital.sqlite3")
    host = os.environ.get("HOSPITAL_API_HOST", "127.0.0.1")
    port = int(os.environ.get("HOSPITAL_API_PORT", "8080"))
    database = HospitalDB(db_path)
    server = ThreadingHTTPServer((host, port), make_handler(database, token))
    print(f"Hospital development API listening on http://{host}:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    serve()
