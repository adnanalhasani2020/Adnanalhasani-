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
from urllib.parse import parse_qs, urlsplit

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
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("Referrer-Policy", "no-referrer")
            self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
            self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")
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
            parsed = urlsplit(self.path)
            route = parsed.path
            if route == "/":
                page = (Path(__file__).parent / "web" / "index.html").read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(page)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("X-Frame-Options", "DENY")
                self.send_header("Referrer-Policy", "no-referrer")
                self.send_header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
                self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'")
                self.end_headers()
                self.wfile.write(page)
                return
            if not self._authorized():
                return
            if route == "/health":
                self._send(200, {"status": "ok", "service": "hospital-api"})
            elif route == "/api/dashboard":
                self._send(200, {"data": database.dashboard()})
            elif route.startswith("/api/") and route.count("/") == 2:
                collections = {
                    "/api/patients": ("SELECT id,patient_no,full_name,phone,active,created_at FROM patients WHERE (?='' OR patient_no LIKE ? OR full_name LIKE ?) ORDER BY id DESC", 2),
                    "/api/doctors": ("SELECT id,employee_no,full_name,specialty,phone,active FROM doctors WHERE (?='' OR employee_no LIKE ? OR full_name LIKE ? OR specialty LIKE ?) ORDER BY id DESC", 3),
                    "/api/appointments": ("SELECT a.id,a.patient_id,p.full_name AS patient_name,a.doctor_id,d.full_name AS doctor_name,a.starts_at,a.ends_at,a.status,a.reason FROM appointments a JOIN patients p ON p.id=a.patient_id JOIN doctors d ON d.id=a.doctor_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR d.full_name LIKE ?) ORDER BY a.starts_at DESC", 3),
                    "/api/lab-orders": ("SELECT l.id,l.patient_id,p.full_name AS patient_name,l.test_name,l.status,l.result,l.ordered_at,l.resulted_at FROM lab_orders l JOIN patients p ON p.id=l.patient_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR l.test_name LIKE ?) ORDER BY l.id DESC", 3),
                    "/api/medicines": ("SELECT id,sku,name,quantity,reorder_level,unit,active FROM medicines WHERE (?='' OR sku LIKE ? OR name LIKE ?) ORDER BY name", 2),
                    "/api/prescriptions": ("SELECT r.id,r.patient_id,p.full_name AS patient_name,r.medicine_id,m.name AS medicine_name,r.dose,r.frequency,r.duration,r.quantity,r.status,r.prescribed_at FROM prescriptions r JOIN patients p ON p.id=r.patient_id JOIN medicines m ON m.id=r.medicine_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR m.name LIKE ?) ORDER BY r.id DESC", 3),
                    "/api/beds": ("SELECT b.id,b.ward,b.bed_no,b.active,CASE WHEN EXISTS(SELECT 1 FROM admissions a WHERE a.bed_id=b.id AND a.status='admitted') THEN 1 ELSE 0 END AS occupied FROM beds b WHERE (?='' OR b.ward LIKE ? OR b.bed_no LIKE ?) ORDER BY b.ward,b.bed_no", 2),
                    "/api/admissions": ("SELECT a.id,a.patient_id,p.full_name AS patient_name,a.bed_id,b.ward,b.bed_no,a.admitted_at,a.discharged_at,a.reason,a.status FROM admissions a JOIN patients p ON p.id=a.patient_id JOIN beds b ON b.id=a.bed_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR b.ward LIKE ? OR b.bed_no LIKE ?) ORDER BY a.id DESC", 4),
                    "/api/invoices": ("SELECT i.id,i.invoice_no,i.patient_id,p.full_name AS patient_name,i.encounter_id,i.total_minor,i.currency,i.status,i.created_at,COALESCE((SELECT SUM(pay.amount_minor) FROM payments pay WHERE pay.invoice_id=i.id),0) AS paid_minor FROM invoices i JOIN patients p ON p.id=i.patient_id WHERE (?='' OR i.invoice_no LIKE ? OR p.full_name LIKE ? OR p.patient_no LIKE ?) ORDER BY i.id DESC", 3),
                }
                config = collections.get(route)
                if config is None:
                    self._send(404, {"error": "not_found"})
                    return
                try:
                    params = parse_qs(parsed.query, keep_blank_values=True)
                    limit = int(params.get("limit", ["50"])[0])
                    offset = int(params.get("offset", ["0"])[0])
                except (TypeError, ValueError, OverflowError):
                    self._send(400, {"error": "invalid_pagination"})
                    return
                if limit < 1 or limit > 100 or offset < 0:
                    self._send(400, {"error": "limit must be 1-100 and offset must be non-negative"})
                    return
                search = params.get("search", [""])[0].strip()[:100]
                sql, search_fields = config
                patterns = ["%" + search + "%"] * search_fields
                with database.connect() as conn:
                    rows = conn.execute(sql + " LIMIT ? OFFSET ?", [search, *patterns, limit, offset]).fetchall()
                self._send(200, {"data": [dict(row) for row in rows],
                                 "pagination": {"limit": limit, "offset": offset, "count": len(rows)}})
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
                elif route == "/api/invoices":
                    self._required(data, "invoice_no", "patient_id", "total_minor", "created_at")
                    ident = database.create_invoice(
                        invoice_no=str(data["invoice_no"]), patient_id=int(data["patient_id"]),
                        total_minor=int(data["total_minor"]), created_at=str(data["created_at"]),
                        actor="api", currency=str(data.get("currency", "YER")),
                        encounter_id=int(data["encounter_id"]) if data.get("encounter_id") is not None else None)
                    self._send(201, {"id": ident, "status": "unpaid"})
                elif route == "/api/payments":
                    self._required(data, "invoice_id", "amount_minor", "method", "received_at")
                    ident = database.record_payment(
                        invoice_id=int(data["invoice_id"]), amount_minor=int(data["amount_minor"]),
                        method=str(data["method"]), received_at=str(data["received_at"]),
                        actor="api", reference=data.get("reference"))
                    self._send(201, {"id": ident, "status": "received"})
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
