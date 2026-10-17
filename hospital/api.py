"""Token-protected local hospital JSON API and Arabic development UI.

Development only: use a long random token, bind to localhost, and never use real
patient data. Production requires per-user authentication and role enforcement.
"""
from __future__ import annotations

import hmac
import ipaddress
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlsplit

from .core import HospitalDB, HospitalError
from .clinical_records import (
    add_diagnosis, add_nursing_note, adjust_stock, create_purchase_order, create_supplier,
    receive_purchase_order, record_allergy, record_vitals, reports,
)
from .security import (AuthStore, AuthenticationError, AuthorizationError,
                       permission_for_route, require_permission)
from .workflows import (
    add_medicine, close_encounter, create_bed, create_encounter, dispense_prescription,
    order_lab_test, prescribe_medicine, update_lab_status,
)


def make_handler(database: HospitalDB, api_token: str, *, auth_mode: str = "token"):
    if auth_mode not in {"token", "users"}:
        raise ValueError("HOSPITAL_AUTH_MODE must be 'token' or 'users'")
    if auth_mode == "token" and (not api_token or len(api_token) < 32):
        raise ValueError("HOSPITAL_API_TOKEN must be set to a secret of at least 32 characters")
    auth_store = AuthStore(database) if auth_mode == "users" else None

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

        def _actor(self) -> str:
            principal = getattr(self, "principal", None)
            return principal.username if principal is not None else "api"

        def _authorized(self) -> bool:
            supplied = self.headers.get("Authorization", "")
            candidate = supplied[7:] if supplied.startswith("Bearer ") else ""
            if auth_mode == "users":
                try:
                    if auth_store is None:
                        raise AuthenticationError("authentication unavailable")
                    self.principal = auth_store.resolve(candidate)
                    permission = permission_for_route(self.command, self.path)
                    if permission:
                        require_permission(self.principal, permission)
                    return True
                except AuthenticationError:
                    self._send(401, {"error": "unauthorized"})
                    return False
                except AuthorizationError:
                    self._send(403, {"error": "forbidden"})
                    return False
            # Development compatibility mode; shared-token mode is not per-user RBAC.
            if not api_token or not candidate or not hmac.compare_digest(candidate, api_token):
                self._send(401, {"error": "unauthorized"})
                return False
            self.principal = None
            return True

        def _body(self) -> dict[str, Any]:
            content_type = self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
            if content_type != "application/json":
                raise HospitalError("Content-Type must be application/json")
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
            if route in ("/", "/settings"):
                page_name = "settings.html" if route == "/settings" else "index.html"
                page = (Path(__file__).parent / "web" / page_name).read_bytes()
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
            if route == "/api/auth/me" and auth_mode == "users":
                self._send(200, {"user": {"id": self.principal.user_id, "username": self.principal.username, "role": self.principal.role, "expires_at": self.principal.expires_at}})
            elif route == "/api/users" and auth_mode == "users":
                with database.connect() as conn:
                    rows = conn.execute("SELECT id,username,role,active,created_at,updated_at FROM auth_users ORDER BY username").fetchall()
                self._send(200, {"data": [dict(row) for row in rows]})
            elif route == "/health":
                self._send(200, {"status": "ok", "service": "hospital-api"})
            elif route == "/api/dashboard":
                self._send(200, {"data": database.dashboard()})
            elif route == "/api/medical-record":
                params = parse_qs(parsed.query, keep_blank_values=True)
                try:
                    patient_id = int(params.get("patient_id", [""])[0])
                except (TypeError, ValueError, OverflowError):
                    self._send(400, {"error": "patient_id_required"})
                    return
                with database.connect() as conn:
                    patient = conn.execute(
                        "SELECT id,patient_no,full_name,date_of_birth,sex,allergies AS legacy_allergies,created_at FROM patients WHERE id=?",
                        (patient_id,)).fetchone()
                    if patient is None:
                        self._send(404, {"error": "patient_not_found"})
                        return
                    def rows(sql, args=(patient_id,)):
                        return [dict(row) for row in conn.execute(sql, args).fetchall()]
                    record = {
                        "patient": dict(patient),
                        "allergies": rows("SELECT id,substance,reaction,severity,status,recorded_by,recorded_at FROM patient_allergies WHERE patient_id=? ORDER BY id DESC"),
                        "encounters": rows("SELECT id,doctor_id,appointment_id,opened_at,closed_at,chief_complaint,diagnosis,clinical_notes,status FROM encounters WHERE patient_id=? ORDER BY opened_at DESC"),
                        "diagnoses": rows("SELECT d.id,d.encounter_id,d.code,d.description,d.diagnosis_type,d.recorded_by,d.recorded_at FROM encounter_diagnoses d JOIN encounters e ON e.id=d.encounter_id WHERE e.patient_id=? ORDER BY d.recorded_at DESC"),
                        "vitals": rows("SELECT id,encounter_id,measured_at,measured_by,temperature_c,pulse_bpm,respiratory_rate,systolic_bp,diastolic_bp,oxygen_saturation,weight_kg,height_cm,pain_score,notes FROM vital_signs WHERE patient_id=? ORDER BY measured_at DESC"),
                        "lab_orders": rows("SELECT id,encounter_id,test_name,status,result,ordered_at,resulted_at FROM lab_orders WHERE patient_id=? ORDER BY ordered_at DESC"),
                        "prescriptions": rows("SELECT r.id,r.encounter_id,m.name AS medicine_name,r.dose,r.frequency,r.duration,r.quantity,r.status,r.prescribed_at FROM prescriptions r JOIN medicines m ON m.id=r.medicine_id WHERE r.patient_id=? ORDER BY r.prescribed_at DESC"),
                        "admissions": rows("SELECT a.id,a.encounter_id,b.ward,b.bed_no,a.admitted_at,a.discharged_at,a.reason,a.status FROM admissions a JOIN beds b ON b.id=a.bed_id WHERE a.patient_id=? ORDER BY a.admitted_at DESC"),
                        "nursing_notes": rows("SELECT id,encounter_id,nurse,note_type,note,recorded_at FROM nursing_notes WHERE patient_id=? ORDER BY recorded_at DESC"),
                    }
                self._send(200, {"data": record})
            elif route == "/api/reports":
                self._send(200, {"data": reports(database)})
            elif route == "/api/search":
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
                if not search:
                    self._send(400, {"error": "search_required"})
                    return
                pattern = "%" + search + "%"
                # Return only fields already exposed by the record explorer; never include
                # patient notes, allergies, or other private free-text fields.
                sql = """
                    SELECT 'patients' AS record_type,id,full_name AS title,patient_no AS subtitle,created_at AS occurred_at,
                           CASE WHEN active=1 THEN 'active' ELSE 'inactive' END AS status
                      FROM patients WHERE patient_no LIKE ? OR full_name LIKE ? OR COALESCE(phone,'') LIKE ?
                    UNION ALL
                    SELECT 'doctors',id,full_name,employee_no || ' — ' || specialty,'',
                           CASE WHEN active=1 THEN 'active' ELSE 'inactive' END
                      FROM doctors WHERE employee_no LIKE ? OR full_name LIKE ? OR specialty LIKE ? OR COALESCE(phone,'') LIKE ?
                    UNION ALL
                    SELECT 'appointments',a.id,p.full_name,d.full_name || ' — ' || a.starts_at,a.starts_at,a.status
                      FROM appointments a JOIN patients p ON p.id=a.patient_id JOIN doctors d ON d.id=a.doctor_id
                     WHERE p.full_name LIKE ? OR p.patient_no LIKE ? OR d.full_name LIKE ? OR COALESCE(a.reason,'') LIKE ?
                    UNION ALL
                    SELECT 'encounters',e.id,p.full_name,COALESCE(d.full_name,'') || ' — ' || e.status,e.opened_at,e.status
                      FROM encounters e JOIN patients p ON p.id=e.patient_id LEFT JOIN doctors d ON d.id=e.doctor_id
                     WHERE p.full_name LIKE ? OR p.patient_no LIKE ? OR COALESCE(d.full_name,'') LIKE ? OR COALESCE(e.diagnosis,'') LIKE ? OR COALESCE(e.chief_complaint,'') LIKE ?
                    UNION ALL
                    SELECT 'lab-orders',l.id,p.full_name,l.test_name,l.ordered_at,l.status
                      FROM lab_orders l JOIN patients p ON p.id=l.patient_id
                     WHERE p.full_name LIKE ? OR p.patient_no LIKE ? OR l.test_name LIKE ? OR l.status LIKE ?
                    UNION ALL
                    SELECT 'medicines',id,name,sku || ' — المخزون: ' || quantity,'',
                           CASE WHEN quantity<=reorder_level THEN 'low_stock' ELSE 'in_stock' END
                      FROM medicines WHERE sku LIKE ? OR name LIKE ?
                    UNION ALL
                    SELECT 'prescriptions',r.id,p.full_name,m.name || ' — ' || r.dose,r.prescribed_at,r.status
                      FROM prescriptions r JOIN patients p ON p.id=r.patient_id JOIN medicines m ON m.id=r.medicine_id
                     WHERE p.full_name LIKE ? OR p.patient_no LIKE ? OR m.name LIKE ? OR r.dose LIKE ?
                    UNION ALL
                    SELECT 'beds',id,ward,bed_no,'',CASE WHEN active=1 THEN 'active' ELSE 'inactive' END
                      FROM beds WHERE ward LIKE ? OR bed_no LIKE ?
                    UNION ALL
                    SELECT 'admissions',a.id,p.full_name,b.ward || ' — ' || b.bed_no,a.admitted_at,a.status
                      FROM admissions a JOIN patients p ON p.id=a.patient_id JOIN beds b ON b.id=a.bed_id
                     WHERE p.full_name LIKE ? OR p.patient_no LIKE ? OR b.ward LIKE ? OR b.bed_no LIKE ?
                    UNION ALL
                    SELECT 'invoices',i.id,i.invoice_no,p.full_name,i.created_at,i.status
                      FROM invoices i JOIN patients p ON p.id=i.patient_id
                     WHERE i.invoice_no LIKE ? OR p.full_name LIKE ? OR p.patient_no LIKE ?
                    UNION ALL
                    SELECT 'audit-log',id,action,entity_type || ':' || COALESCE(entity_id,''),occurred_at,action
                      FROM audit_log WHERE actor LIKE ? OR action LIKE ? OR entity_type LIKE ? OR COALESCE(entity_id,'') LIKE ? OR COALESCE(details_json,'') LIKE ?
                    ORDER BY occurred_at DESC,record_type,id DESC LIMIT ? OFFSET ?
                """
                with database.connect() as conn:
                    rows = conn.execute(sql, [pattern] * 40 + [limit, offset]).fetchall()
                self._send(200, {"data": [dict(row) for row in rows],
                                 "pagination": {"limit": limit, "offset": offset, "count": len(rows)}})
            elif route == "/api/audit-log":
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
                pattern = "%" + search + "%"
                with database.connect() as conn:
                    rows = conn.execute(
                        """SELECT id,actor,action,entity_type,entity_id,details_json,occurred_at
                           FROM audit_log
                           WHERE (?='' OR actor LIKE ? OR action LIKE ?
                                  OR entity_type LIKE ? OR entity_id LIKE ? OR details_json LIKE ?)
                           ORDER BY id DESC LIMIT ? OFFSET ?""",
                        [search, pattern, pattern, pattern, pattern, pattern, limit, offset],
                    ).fetchall()
                self._send(200, {"data": [dict(row) for row in rows],
                                 "pagination": {"limit": limit, "offset": offset, "count": len(rows)}})
            elif route.startswith("/api/") and route.count("/") == 2:
                collections = {
                    "/api/patients": ("SELECT id,patient_no,full_name,phone,active,created_at FROM patients WHERE (?='' OR patient_no LIKE ? OR full_name LIKE ?) ORDER BY id DESC", 2),
                    "/api/doctors": ("SELECT id,employee_no,full_name,specialty,phone,active FROM doctors WHERE (?='' OR employee_no LIKE ? OR full_name LIKE ? OR specialty LIKE ?) ORDER BY id DESC", 3),
                    "/api/appointments": ("SELECT a.id,a.patient_id,p.full_name AS patient_name,a.doctor_id,d.full_name AS doctor_name,a.starts_at,a.ends_at,a.status,a.reason FROM appointments a JOIN patients p ON p.id=a.patient_id JOIN doctors d ON d.id=a.doctor_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR d.full_name LIKE ?) ORDER BY a.starts_at DESC", 3),
                    "/api/encounters": ("SELECT e.id,e.patient_id,p.full_name AS patient_name,e.doctor_id,d.full_name AS doctor_name,e.appointment_id,e.opened_at,e.closed_at,e.chief_complaint,e.diagnosis,e.status FROM encounters e JOIN patients p ON p.id=e.patient_id LEFT JOIN doctors d ON d.id=e.doctor_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR COALESCE(d.full_name,'') LIKE ? OR e.status LIKE ?) ORDER BY e.id DESC", 4),
                    "/api/lab-orders": ("SELECT l.id,l.patient_id,p.full_name AS patient_name,l.encounter_id,l.test_name,l.status,l.result,l.ordered_at,l.resulted_at FROM lab_orders l JOIN patients p ON p.id=l.patient_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR l.test_name LIKE ?) ORDER BY l.id DESC", 3),
                    "/api/medicines": ("SELECT id,sku,name,quantity,reorder_level,unit,active FROM medicines WHERE (?='' OR sku LIKE ? OR name LIKE ?) ORDER BY name", 2),
                    "/api/prescriptions": ("SELECT r.id,r.patient_id,p.full_name AS patient_name,r.encounter_id,r.medicine_id,m.name AS medicine_name,r.dose,r.frequency,r.duration,r.quantity,r.status,r.prescribed_at FROM prescriptions r JOIN patients p ON p.id=r.patient_id JOIN medicines m ON m.id=r.medicine_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR m.name LIKE ?) ORDER BY r.id DESC", 3),
                    "/api/beds": ("SELECT b.id,b.ward,b.bed_no,b.active,CASE WHEN EXISTS(SELECT 1 FROM admissions a WHERE a.bed_id=b.id AND a.status='admitted') THEN 1 ELSE 0 END AS occupied FROM beds b WHERE (?='' OR b.ward LIKE ? OR b.bed_no LIKE ?) ORDER BY b.ward,b.bed_no", 2),
                    "/api/admissions": ("SELECT a.id,a.patient_id,p.full_name AS patient_name,a.encounter_id,a.bed_id,b.ward,b.bed_no,a.admitted_at,a.discharged_at,a.reason,a.status FROM admissions a JOIN patients p ON p.id=a.patient_id JOIN beds b ON b.id=a.bed_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR b.ward LIKE ? OR b.bed_no LIKE ?) ORDER BY a.id DESC", 4),
                    "/api/invoices": ("SELECT i.id,i.invoice_no,i.patient_id,p.full_name AS patient_name,i.encounter_id,i.total_minor,i.currency,i.status,i.created_at,COALESCE((SELECT SUM(pay.amount_minor) FROM payments pay WHERE pay.invoice_id=i.id),0) AS paid_minor FROM invoices i JOIN patients p ON p.id=i.patient_id WHERE (?='' OR i.invoice_no LIKE ? OR p.full_name LIKE ? OR p.patient_no LIKE ?) ORDER BY i.id DESC", 3),
                    "/api/diagnoses": ("SELECT d.id,d.encounter_id,e.patient_id,p.full_name AS patient_name,d.code,d.description,d.diagnosis_type,d.recorded_by,d.recorded_at FROM encounter_diagnoses d JOIN encounters e ON e.id=d.encounter_id JOIN patients p ON p.id=e.patient_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR d.description LIKE ?) ORDER BY d.id DESC", 3),
                    "/api/allergies": ("SELECT a.id,a.patient_id,p.full_name AS patient_name,a.substance,a.reaction,a.severity,a.status,a.recorded_by,a.recorded_at FROM patient_allergies a JOIN patients p ON p.id=a.patient_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR a.substance LIKE ?) ORDER BY a.id DESC", 3),
                    "/api/vitals": ("SELECT v.id,v.encounter_id,v.patient_id,p.full_name AS patient_name,v.measured_at,v.measured_by,v.temperature_c,v.pulse_bpm,v.respiratory_rate,v.systolic_bp,v.diastolic_bp,v.oxygen_saturation,v.weight_kg,v.height_cm,v.pain_score,v.notes FROM vital_signs v JOIN patients p ON p.id=v.patient_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR v.measured_by LIKE ?) ORDER BY v.measured_at DESC", 3),
                    "/api/nursing-notes": ("SELECT n.id,n.encounter_id,n.patient_id,p.full_name AS patient_name,n.nurse,n.note_type,n.note,n.recorded_at FROM nursing_notes n JOIN patients p ON p.id=n.patient_id WHERE (?='' OR p.full_name LIKE ? OR p.patient_no LIKE ? OR n.nurse LIKE ?) ORDER BY n.recorded_at DESC", 3),
                    "/api/inventory-movements": ("SELECT m.id,m.medicine_id,i.name AS medicine_name,m.movement_type,m.quantity_delta,m.reference_type,m.reference_id,m.notes,m.actor,m.occurred_at FROM inventory_movements m JOIN medicines i ON i.id=m.medicine_id WHERE (?='' OR i.name LIKE ? OR i.sku LIKE ?) ORDER BY m.id DESC", 2),
                    "/api/suppliers": ("SELECT id,supplier_no,name,phone,active,created_at FROM suppliers WHERE (?='' OR supplier_no LIKE ? OR name LIKE ?) ORDER BY name", 2),
                    "/api/purchase-orders": ("SELECT o.id,o.order_no,o.supplier_id,s.name AS supplier_name,o.status,o.ordered_at,o.received_at,o.actor,o.notes,(SELECT COUNT(*) FROM purchase_order_items i WHERE i.purchase_order_id=o.id) AS item_count FROM purchase_orders o JOIN suppliers s ON s.id=o.supplier_id WHERE (?='' OR o.order_no LIKE ? OR s.name LIKE ?) ORDER BY o.id DESC", 2),
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
            route = self.path.split("?", 1)[0]
            login_route = route == "/api/auth/login" and auth_mode == "users"
            if not login_route and not self._authorized():
                return
            try:
                parts = route.strip("/").split("/")
                data = self._body()
                if login_route:
                    self._required(data, "username", "password")
                    token, principal = auth_store.authenticate(username=str(data["username"]), password=str(data["password"]))
                    self._send(200, {"token": token, "user": {"id": principal.user_id, "username": principal.username, "role": principal.role, "expires_at": principal.expires_at}})
                elif route == "/api/auth/logout" and auth_mode == "users":
                    auth_store.revoke(self.headers.get("Authorization", "")[7:])
                    self._send(200, {"status": "logged_out"})
                elif len(parts) == 4 and parts[:2] == ["api", "users"] and parts[3] == "active" and auth_mode == "users":
                    self._required(data, "active", "at")
                    if not isinstance(data["active"], bool):
                        raise HospitalError("active must be a boolean")
                    auth_store.set_user_active(user_id=int(parts[2]), active=data["active"], at=str(data["at"]), actor=self._actor())
                    self._send(200, {"id": int(parts[2]), "active": data["active"]})
                elif route == "/api/users" and auth_mode == "users":
                    self._required(data, "username", "password", "role", "at")
                    ident = auth_store.create_user(username=str(data["username"]), password=str(data["password"]), role=str(data["role"]), at=str(data["at"]), actor=self._actor())
                    self._send(201, {"id": ident, "status": "created"})
                elif route == "/api/patients":
                    self._required(data, "patient_no", "full_name", "created_at")
                    ident = database.register_patient(
                        patient_no=str(data["patient_no"]), full_name=str(data["full_name"]),
                        created_at=str(data["created_at"]), actor=self._actor(),
                        date_of_birth=data.get("date_of_birth"), sex=data.get("sex"),
                        phone=data.get("phone"), address=data.get("address"),
                        emergency_contact=data.get("emergency_contact"),
                        allergies=str(data.get("allergies", "")), notes=str(data.get("notes", "")))
                    self._send(201, {"id": ident, "status": "created"})
                elif route == "/api/doctors":
                    self._required(data, "employee_no", "full_name", "specialty", "at")
                    ident = database.register_doctor(employee_no=str(data["employee_no"]),
                        full_name=str(data["full_name"]), specialty=str(data["specialty"]),
                        at=str(data["at"]), actor=self._actor(), phone=data.get("phone"))
                    self._send(201, {"id": ident, "status": "created"})
                elif route == "/api/appointments":
                    self._required(data, "patient_id", "doctor_id", "starts_at", "ends_at", "created_at")
                    ident = database.book_appointment(patient_id=int(data["patient_id"]),
                        doctor_id=int(data["doctor_id"]), starts_at=str(data["starts_at"]),
                        ends_at=str(data["ends_at"]), created_at=str(data["created_at"]),
                        actor=self._actor(), reason=str(data.get("reason", "")))
                    self._send(201, {"id": ident, "status": "scheduled"})
                elif route == "/api/encounters":
                    self._required(data, "patient_id", "opened_at")
                    ident = create_encounter(database, patient_id=int(data["patient_id"]),
                        doctor_id=int(data["doctor_id"]) if data.get("doctor_id") is not None else None,
                        opened_at=str(data["opened_at"]), actor=self._actor(),
                        chief_complaint=str(data.get("chief_complaint", "")),
                        appointment_id=int(data["appointment_id"]) if data.get("appointment_id") is not None else None)
                    self._send(201, {"id": ident, "status": "open"})
                elif len(parts) == 4 and parts[:2] == ["api", "encounters"] and parts[3] == "close":
                    self._required(data, "closed_at", "diagnosis")
                    close_encounter(database, encounter_id=int(parts[2]), closed_at=str(data["closed_at"]),
                        actor=self._actor(), diagnosis=str(data["diagnosis"]),
                        clinical_notes=str(data.get("clinical_notes", "")))
                    self._send(200, {"id": int(parts[2]), "status": "closed"})
                elif route == "/api/lab-orders":
                    self._required(data, "patient_id", "test_name", "ordered_at")
                    ident = order_lab_test(database, patient_id=int(data["patient_id"]),
                        test_name=str(data["test_name"]), ordered_at=str(data["ordered_at"]),
                        actor=self._actor(), doctor_id=int(data["doctor_id"]) if data.get("doctor_id") is not None else None,
                        encounter_id=int(data["encounter_id"]) if data.get("encounter_id") is not None else None)
                    self._send(201, {"id": ident, "status": "ordered"})
                elif len(parts) == 4 and parts[:2] == ["api", "lab-orders"] and parts[3] == "status":
                    self._required(data, "status", "at")
                    update_lab_status(database, order_id=int(parts[2]), status=str(data["status"]),
                        actor=self._actor(), at=str(data["at"]), result=data.get("result"))
                    self._send(200, {"id": int(parts[2]), "status": str(data["status"])})
                elif route == "/api/medicines":
                    self._required(data, "sku", "name", "quantity", "reorder_level", "at")
                    ident = add_medicine(database, sku=str(data["sku"]), name=str(data["name"]),
                        quantity=int(data["quantity"]), reorder_level=int(data["reorder_level"]),
                        actor=self._actor(), at=str(data["at"]), unit=str(data.get("unit", "unit")))
                    self._send(201, {"id": ident, "status": "created"})
                elif route == "/api/prescriptions":
                    self._required(data, "patient_id", "medicine_id", "dose", "frequency", "duration", "quantity", "prescribed_at")
                    ident = prescribe_medicine(database, patient_id=int(data["patient_id"]),
                        medicine_id=int(data["medicine_id"]), dose=str(data["dose"]),
                        frequency=str(data["frequency"]), duration=str(data["duration"]),
                        quantity=int(data["quantity"]), prescribed_at=str(data["prescribed_at"]),
                        actor=self._actor(), encounter_id=int(data["encounter_id"]) if data.get("encounter_id") is not None else None)
                    self._send(201, {"id": ident, "status": "pending"})
                elif len(parts) == 4 and parts[:2] == ["api", "prescriptions"] and parts[3] == "dispense":
                    self._required(data, "dispensed_at")
                    dispense_prescription(database, prescription_id=int(parts[2]),
                        dispensed_at=str(data["dispensed_at"]), actor=self._actor())
                    self._send(200, {"id": int(parts[2]), "status": "dispensed"})
                elif route == "/api/beds":
                    self._required(data, "ward", "bed_no", "at")
                    ident = create_bed(database, ward=str(data["ward"]), bed_no=str(data["bed_no"]),
                        actor=self._actor(), at=str(data["at"]))
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
                        encounter_id = int(data["encounter_id"]) if data.get("encounter_id") is not None else None
                        if encounter_id is not None:
                            encounter = conn.execute("SELECT patient_id FROM encounters WHERE id=?", (encounter_id,)).fetchone()
                            if encounter is None or encounter["patient_id"] != int(data["patient_id"]):
                                raise HospitalError("encounter does not belong to this patient")
                        cur = conn.execute("INSERT INTO admissions(patient_id,bed_id,admitted_at,reason,encounter_id) VALUES(?,?,?,?,?)",
                            (int(data["patient_id"]), int(data["bed_id"]), str(data["admitted_at"]), str(data.get("reason", "")), encounter_id))
                        ident = cur.lastrowid
                        database._audit(conn, self._actor(), "patient.admitted", "admission", ident, str(data["admitted_at"]))
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
                        database._audit(conn, self._actor(), "patient.discharged", "admission", int(parts[2]), str(data["discharged_at"]))
                        conn.commit()
                    self._send(200, {"id": int(parts[2]), "status": "discharged"})
                elif route == "/api/diagnoses":
                    self._required(data, "encounter_id", "description", "at")
                    ident = add_diagnosis(database, encounter_id=int(data["encounter_id"]),
                        description=str(data["description"]), actor=self._actor(), at=str(data["at"]),
                        code=str(data.get("code", "")), diagnosis_type=str(data.get("diagnosis_type", "clinical")))
                    self._send(201, {"id": ident, "status": "recorded"})
                elif route == "/api/allergies":
                    self._required(data, "patient_id", "substance", "at")
                    ident = record_allergy(database, patient_id=int(data["patient_id"]),
                        substance=str(data["substance"]), actor=self._actor(), at=str(data["at"]),
                        reaction=str(data.get("reaction", "")), severity=str(data.get("severity", "unknown")),
                        status=str(data.get("status", "active")))
                    self._send(201, {"id": ident, "status": "recorded"})
                elif route == "/api/vitals":
                    self._required(data, "encounter_id", "measured_at")
                    fields = ("temperature_c", "pulse_bpm", "respiratory_rate", "systolic_bp",
                              "diastolic_bp", "oxygen_saturation", "weight_kg", "height_cm", "pain_score")
                    values = {key: data.get(key) for key in fields}
                    ident = record_vitals(database, encounter_id=int(data["encounter_id"]),
                        measured_at=str(data["measured_at"]), actor=self._actor(),
                        patient_id=int(data["patient_id"]) if data.get("patient_id") is not None else None,
                        notes=str(data.get("notes", "")), **values)
                    self._send(201, {"id": ident, "status": "recorded"})
                elif route == "/api/nursing-notes":
                    self._required(data, "encounter_id", "note", "at")
                    ident = add_nursing_note(database, encounter_id=int(data["encounter_id"]),
                        note=str(data["note"]), actor=self._actor(), at=str(data["at"]),
                        note_type=str(data.get("note_type", "observation")))
                    self._send(201, {"id": ident, "status": "recorded"})
                elif route == "/api/inventory-adjustments":
                    self._required(data, "medicine_id", "quantity_delta", "at")
                    ident = adjust_stock(database, medicine_id=int(data["medicine_id"]),
                        quantity_delta=int(data["quantity_delta"]), actor=self._actor(), at=str(data["at"]),
                        notes=str(data.get("notes", "")), movement_type=str(data.get("movement_type", "adjustment")))
                    self._send(201, {"id": ident, "status": "recorded"})
                elif route == "/api/suppliers":
                    self._required(data, "supplier_no", "name", "at")
                    ident = create_supplier(database, supplier_no=str(data["supplier_no"]),
                        name=str(data["name"]), actor=self._actor(), at=str(data["at"]),
                        phone=str(data.get("phone", "")))
                    self._send(201, {"id": ident, "status": "created"})
                elif route == "/api/purchase-orders":
                    self._required(data, "order_no", "supplier_id", "items", "at")
                    ident = create_purchase_order(database, order_no=str(data["order_no"]),
                        supplier_id=int(data["supplier_id"]), items=data["items"], actor=self._actor(),
                        at=str(data["at"]), notes=str(data.get("notes", "")))
                    self._send(201, {"id": ident, "status": "ordered"})
                elif len(parts) == 4 and parts[:2] == ["api", "purchase-orders"] and parts[3] == "receive":
                    self._required(data, "at")
                    receive_purchase_order(database, order_id=int(parts[2]), actor=self._actor(), at=str(data["at"]))
                    self._send(200, {"id": int(parts[2]), "status": "received"})
                elif route == "/api/invoices":
                    self._required(data, "invoice_no", "patient_id", "total_minor", "created_at")
                    ident = database.create_invoice(
                        invoice_no=str(data["invoice_no"]), patient_id=int(data["patient_id"]),
                        total_minor=int(data["total_minor"]), created_at=str(data["created_at"]),
                        actor=self._actor(), currency=str(data.get("currency", "YER")),
                        encounter_id=int(data["encounter_id"]) if data.get("encounter_id") is not None else None)
                    self._send(201, {"id": ident, "status": "unpaid"})
                elif route == "/api/payments":
                    self._required(data, "invoice_id", "amount_minor", "method", "received_at")
                    ident = database.record_payment(
                        invoice_id=int(data["invoice_id"]), amount_minor=int(data["amount_minor"]),
                        method=str(data["method"]), received_at=str(data["received_at"]),
                        actor=self._actor(), reference=data.get("reference"))
                    self._send(201, {"id": ident, "status": "received"})
                else:
                    self._send(404, {"error": "not_found"})
            except AuthenticationError:
                self._send(401, {"error": "unauthorized"})
            except HospitalError as exc:
                self._send(400, {"error": str(exc)})
            except (TypeError, ValueError, OverflowError):
                self._send(400, {"error": "invalid_field_value"})
            except Exception:
                self._send(500, {"error": "internal_server_error"})

    return Handler


def _validate_bind_host(host: str, *, allow_remote: bool = False) -> str:
    """Keep the shared-token development server loopback-only by default."""
    if allow_remote:
        return host
    if host.strip().lower() == "localhost":
        return host
    try:
        address = ipaddress.ip_address(host)
    except ValueError as exc:
        raise ValueError(
            "HOSPITAL_API_HOST must be a loopback address unless "
            "HOSPITAL_API_ALLOW_REMOTE=1 is explicitly set"
        ) from exc
    if not address.is_loopback:
        raise ValueError(
            "HOSPITAL_API_HOST must be a loopback address unless "
            "HOSPITAL_API_ALLOW_REMOTE=1 is explicitly set"
        )
    return host


def serve() -> None:
    token = os.environ.get("HOSPITAL_API_TOKEN", "")
    auth_mode = os.environ.get("HOSPITAL_AUTH_MODE", "token").strip().lower()
    db_path = os.environ.get("HOSPITAL_DB_PATH", "hospital.sqlite3")
    host = os.environ.get("HOSPITAL_API_HOST", "127.0.0.1")
    allow_remote = os.environ.get("HOSPITAL_API_ALLOW_REMOTE", "") == "1"
    host = _validate_bind_host(host, allow_remote=allow_remote)
    port = int(os.environ.get("HOSPITAL_API_PORT", "8080"))
    database = HospitalDB(db_path)
    server = ThreadingHTTPServer((host, port), make_handler(database, token, auth_mode=auth_mode))
    print(f"Hospital development UI/API listening on http://{host}:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    serve()
