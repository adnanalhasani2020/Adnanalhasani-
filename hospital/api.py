"""Token-protected local hospital JSON API and Arabic development UI.

Development only: use a long random token, bind to localhost, and never use real
patient data. Production requires per-user authentication and role enforcement.
"""
from __future__ import annotations

import hmac
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from .core import HospitalDB, HospitalError
from .workflows import (
    add_medicine, close_encounter, create_bed, create_encounter, dispense_prescription,
    order_lab_test, prescribe_medicine, update_lab_status,
)


def make_handler(database: HospitalDB, api_token: str):
    if not api_token or len(api_token) < 32:
        raise ValueError("HOSPITAL_API_TOKEN must be set to a secret of at least 32 characters")

    class Handler(BaseHTTPRequestHandler):
        server_version = "HospitalDevAPI/0.3"

        def log_message(self, format: str, *args: Any) -> None:
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
            candidate = supplied[7:] if supplied.startswith("Bearer ") else ""
            if not hmac.compare_digest(candidate, api_token):
                self._send(401, {"error": "unauthorized"})
                return False
            return True

        def _body(self) -> dict[str, Any]:
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError as exc:
                raise HospitalError("invalid content length") from exc
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
            route = self.path.split("?", 1)[0]
            if route == "/":
                page = (Path(__file__).parent / "web" / "index.html").read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(page)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.end_headers()
                self.wfile.write(page)
                return
            if not self._authorized():
                return
            if route == "/health":
                self._send(200, {"status": "ok", "service": "hospital-api"})
            elif route == "/api/dashboard":
                self._send(200, {"data": database.dashboard()})
            else:
                self._send(404, {"error": "not_found"})

        def do_POST(self) -> None:
            if not self._authorized():
                return
            try:
                route = self.path.split("?", 1)[0]
                parts = route.strip("/").split("/")
                data = self._body()
                if route == "/api/patients":
                    self._required(data, "patient_no", "full_name", "created_at")
                    ident = database.register_patient(
                        patient_no=str(data["patient_no"]), full_name=str(data["full_name"]),
                        created_at=str(data["created_at"]), actor="api",
                        date_of_birth=data.get("date_of_birth"), sex=data.get("sex"),
                        phone=data.get("phone"), address=data.get("address"),
                        emergency_contact=data.get("emergency_contact"),
                        allergies=str(data.get("allergies", "")), notes=str(data.get("notes", "")))
                    self._send(201, {"id": ident, "status": "created"})
                elif route == "/api/doctors":
                    self._required(data, "employee_no", "full_name", "specialty", "at")
                    ident = database.register_doctor(employee_no=str(data["employee_no"]),
                        full_name=str(data["full_name"]), specialty=str(data["specialty"]),
                        at=str(data["at"]), actor="api", phone=data.get("phone"))
                    self._send(201, {"id": ident, "status": "created"})
                elif route == "/api/appointments":
                    self._required(data, "patient_id", "doctor_id", "starts_at", "ends_at", "created_at")
                    ident = database.book_appointment(patient_id=int(data["patient_id"]),
                        doctor_id=int(data["doctor_id"]), starts_at=str(data["starts_at"]),
                        ends_at=str(data["ends_at"]), created_at=str(data["created_at"]),
                        actor="api", reason=str(data.get("reason", "")))
                    self._send(201, {"id": ident, "status": "scheduled"})
                elif route == "/api/encounters":
                    self._required(data, "patient_id", "opened_at")
                    ident = create_encounter(database, patient_id=int(data["patient_id"]),
                        doctor_id=int(data["doctor_id"]) if data.get("doctor_id") is not None else None,
                        opened_at=str(data["opened_at"]), actor="api",
                        chief_complaint=str(data.get("chief_complaint", "")),
                        appointment_id=int(data["appointment_id"]) if data.get("appointment_id") is not None else None)
                    self._send(201, {"id": ident, "status": "open"})
                elif len(parts) == 4 and parts[:2] == ["api", "encounters"] and parts[3] == "close":
                    self._required(data, "closed_at", "diagnosis")
                    close_encounter(database, encounter_id=int(parts[2]), closed_at=str(data["closed_at"]),
                        actor="api", diagnosis=str(data["diagnosis"]),
                        clinical_notes=str(data.get("clinical_notes", "")))
                    self._send(200, {"id": int(parts[2]), "status": "closed"})
                elif route == "/api/lab-orders":
                    self._required(data, "patient_id", "test_name", "ordered_at")
                    ident = order_lab_test(database, patient_id=int(data["patient_id"]),
                        test_name=str(data["test_name"]), ordered_at=str(data["ordered_at"]),
                        actor="api", doctor_id=int(data["doctor_id"]) if data.get("doctor_id") is not None else None,
                        encounter_id=int(data["encounter_id"]) if data.get("encounter_id") is not None else None)
                    self._send(201, {"id": ident, "status": "ordered"})
                elif len(parts) == 4 and parts[:2] == ["api", "lab-orders"] and parts[3] == "status":
                    self._required(data, "status", "at")
                    update_lab_status(database, order_id=int(parts[2]), status=str(data["status"]),
                        actor="api", at=str(data["at"]), result=data.get("result"))
                    self._send(200, {"id": int(parts[2]), "status": str(data["status"])})
                elif route == "/api/medicines":
                    self._required(data, "sku", "name", "quantity", "reorder_level", "at")
                    ident = add_medicine(database, sku=str(data["sku"]), name=str(data["name"]),
                        quantity=int(data["quantity"]), reorder_level=int(data["reorder_level"]),
                        actor="api", at=str(data["at"]), unit=str(data.get("unit", "unit")))
                    self._send(201, {"id": ident, "status": "created"})
                elif route == "/api/prescriptions":
                    self._required(data, "patient_id", "medicine_id", "dose", "frequency", "duration", "quantity", "prescribed_at")
                    ident = prescribe_medicine(database, patient_id=int(data["patient_id"]),
                        medicine_id=int(data["medicine_id"]), dose=str(data["dose"]),
                        frequency=str(data["frequency"]), duration=str(data["duration"]),
                        quantity=int(data["quantity"]), prescribed_at=str(data["prescribed_at"]),
                        actor="api", encounter_id=int(data["encounter_id"]) if data.get("encounter_id") is not None else None)
                    self._send(201, {"id": ident, "status": "pending"})
                elif len(parts) == 4 and parts[:2] == ["api", "prescriptions"] and parts[3] == "dispense":
                    self._required(data, "dispensed_at")
                    dispense_prescription(database, prescription_id=int(parts[2]),
                        dispensed_at=str(data["dispensed_at"]), actor="api")
                    self._send(200, {"id": int(parts[2]), "status": "dispensed"})
                elif route == "/api/beds":
                    self._required(data, "ward", "bed_no", "at")
                    ident = create_bed(database, ward=str(data["ward"]), bed_no=str(data["bed_no"]),
                        actor="api", at=str(data["at"]))
                    self._send(201, {"id": ident, "status": "created"})
                elif route == "/api/admissions":
                    self._required(data, "patient_id", "bed_id", "admitted_at")
                    with database.connect() as conn:
                        conn.execute("BEGIN IMMEDIATE")
                        patient = conn.execute("SELECT active FROM patients WHERE id=?", (int(data["patient_id"]),)).fetchone()
                        bed = conn.execute("SELECT active FROM beds WHERE id=?", (int(data["bed_id"]),)).fetchone()
                        occupied = conn.execute("SELECT 1 FROM admissions WHERE bed_id=? AND status='admitted'", (int(data["bed_id"]),)).fetchone()
                        if patient is None or not patient["active"]:
                            raise HospitalError("active patient not found")
                        if bed is None or not bed["active"]:
                            raise HospitalError("active bed not found")
                        if occupied:
                            raise HospitalError("bed is already occupied")
                        cur = conn.execute("INSERT INTO admissions(patient_id,bed_id,admitted_at,reason) VALUES(?,?,?,?)",
                            (int(data["patient_id"]), int(data["bed_id"]), str(data["admitted_at"]), str(data.get("reason", ""))))
                        ident = cur.lastrowid
                        database._audit(conn, "api", "patient.admitted", "admission", ident, str(data["admitted_at"]))
                        conn.commit()
                    self._send(201, {"id": int(ident), "status": "admitted"})
                elif len(parts) == 4 and parts[:2] == ["api", "admissions"] and parts[3] == "discharge":
                    self._required(data, "discharged_at")
                    with database.connect() as conn:
                        conn.execute("BEGIN IMMEDIATE")
                        row = conn.execute("SELECT admitted_at,status FROM admissions WHERE id=?", (int(parts[2]),)).fetchone()
                        if row is None or row["status"] != "admitted":
                            raise HospitalError("active admission not found")
                        if str(data["discharged_at"]) < row["admitted_at"]:
                            raise HospitalError("discharge cannot precede admission")
                        conn.execute("UPDATE admissions SET discharged_at=?,status='discharged' WHERE id=?",
                            (str(data["discharged_at"]), int(parts[2])))
                        database._audit(conn, "api", "patient.discharged", "admission", int(parts[2]), str(data["discharged_at"]))
                        conn.commit()
                    self._send(200, {"id": int(parts[2]), "status": "discharged"})
                else:
                    self._send(404, {"error": "not_found"})
            except HospitalError as exc:
                self._send(400, {"error": str(exc)})
            except (TypeError, ValueError, OverflowError):
                self._send(400, {"error": "invalid_field_value"})
            except Exception:
                self._send(500, {"error": "internal_server_error"})

    return Handler


def serve() -> None:
    token = os.environ.get("HOSPITAL_API_TOKEN", "")
    db_path = os.environ.get("HOSPITAL_DB_PATH", "hospital.sqlite3")
    host = os.environ.get("HOSPITAL_API_HOST", "127.0.0.1")
    port = int(os.environ.get("HOSPITAL_API_PORT", "8080"))
    database = HospitalDB(db_path)
    server = ThreadingHTTPServer((host, port), make_handler(database, token))
    print(f"Hospital development UI/API listening on http://{host}:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    serve()
