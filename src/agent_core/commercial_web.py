"""Dependency-free WSGI interface for the commercial MVP.

Run with: python -m agent_core.commercial_web
Bind to loopback by default. Put a TLS reverse proxy in front before public exposure.
"""
from __future__ import annotations

import json
import logging
import os
import secrets
import sqlite3
from http.cookies import SimpleCookie
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from wsgiref.simple_server import WSGIServer, make_server, WSGIRequestHandler
from socketserver import ThreadingMixIn

from agent_core.commerce_checkout import CommercialCheckoutApplication
from agent_core.commercial_identity import CommercialIdentityApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError


PAGE = r"""<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>المتجر — النسخة التجارية الأولى</title>
<style>
body{font-family:system-ui,sans-serif;max-width:980px;margin:2rem auto;padding:0 1rem;background:#f6f7f9;color:#18212f}
main{background:white;padding:1.25rem;border-radius:14px;box-shadow:0 3px 20px #0001}
form,.row{display:flex;gap:.6rem;flex-wrap:wrap;margin:.7rem 0}input,button{font:inherit;padding:.65rem;border:1px solid #ccd2db;border-radius:8px}input{min-width:150px;max-width:100%}button{cursor:pointer;background:#173b67;color:white;border:0}button:disabled{opacity:.55;cursor:wait}.card{border:1px solid #e0e4ea;border-radius:10px;padding:1rem;margin:.7rem 0}.muted{color:#596579}.hidden{display:none}#message{white-space:pre-wrap;padding:.6rem}#catalog{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:.8rem}
</style></head>
<body><main>
<h1>المتجر</h1><p class="muted">نسخة تجريبية تجارية: عرض المنتجات ذات المخزون المثبت فقط، ثم تنفيذ بيع ذري مع إيصال.</p>
<section id="auth">
<h2>تسجيل الدخول أو إنشاء حساب</h2>
<form id="login"><input name="username" autocomplete="username" placeholder="اسم المستخدم" required><input name="password" type="password" autocomplete="current-password" placeholder="كلمة المرور (12 حرفاً على الأقل)" required><button>دخول</button></form>
<form id="register"><input name="username" autocomplete="username" placeholder="اسم مستخدم جديد" required><input name="password" type="password" autocomplete="new-password" placeholder="كلمة مرور قوية" required><button>إنشاء حساب</button></form>
</section>
<section id="shop" class="hidden">
<div class="row"><strong id="who"></strong><button id="refresh">تحديث المنتجات</button><button id="logout">تسجيل الخروج</button></div>
<div id="catalog"></div>
<h2>عملياتي الأخيرة</h2><button id="refreshReceipts" type="button">تحديث سجل المبيعات</button><div id="receipts"></div>
</section><div id="message" role="status" aria-live="polite"></div>
</main>
<script>
const $=id=>document.getElementById(id);
const message=text=>{$('message').textContent=text};
async function api(path,opts={}){const r=await fetch(path,{credentials:'same-origin',...opts,headers:{'Content-Type':'application/json',...(opts.headers||{})}});let data={};try{data=await r.json()}catch{}if(!r.ok)throw Error(data.error||'تعذر تنفيذ الطلب');return data}
function formData(form){return Object.fromEntries(new FormData(form).entries())}
async function loadMe(){try{const me=await api('/api/me');$('auth').classList.add('hidden');$('shop').classList.remove('hidden');$('who').textContent='أهلاً، '+me.username;$('catalog').replaceChildren();await loadCatalog();await loadRecentReceipts()}catch{$('auth').classList.remove('hidden');$('shop').classList.add('hidden')}}
async function loadRecentReceipts(){const root=$('receipts');root.replaceChildren();try{const data=await api('/api/receipts');if(!data.items.length){root.textContent='لا توجد عمليات بيع سابقة لهذا الحساب.';return}for(const receipt of data.items){const card=document.createElement('article');card.className='card';const title=document.createElement('h3');title.textContent='عملية '+receipt.sale_id;const detail=document.createElement('p');detail.textContent='الحالة: '+receipt.state+' — الكمية: '+receipt.quantity+' — الإجمالي: '+receipt.total_minor+' '+receipt.currency_code;const remaining=document.createElement('p');remaining.textContent='المتبقي عند البيع: '+receipt.quantity_remaining+' — الوقت: '+receipt.occurred_at;card.append(title,detail,remaining);root.append(card)}}catch(e){root.textContent=e.message}}
async function loadCatalog(){message('جارٍ قراءة المنتجات والمخزون الفعلي...');try{const data=await api('/api/catalog');const root=$('catalog');root.replaceChildren();for(const item of data.items){const card=document.createElement('article');card.className='card';const title=document.createElement('h3');title.textContent=item.product_name;const detail=document.createElement('p');detail.textContent='السعر: '+item.unit_price_minor+' '+item.currency_code+' (وحدات نقدية صغرى)';const stock=document.createElement('p');stock.textContent='المتاح فعلياً: '+item.available_quantity;const context=document.createElement('p');context.className='muted';context.textContent='النشاط: '+item.activity_id+' — الموقع: '+(item.location_ref||'غير محدد')+' — النطاق: '+item.scope_key;const qty=document.createElement('input');qty.type='number';qty.min='1';qty.max=String(item.available_quantity);qty.value='1';qty.setAttribute('aria-label','الكمية');const total=document.createElement('p');total.setAttribute('aria-live','polite');const updateTotal=()=>{const count=Number(qty.value);const amount=count*item.unit_price_minor;if(!Number.isSafeInteger(count)||count<1||count>item.available_quantity||!Number.isSafeInteger(amount)||amount<0){total.textContent='أدخل كمية صحيحة ضمن المتاح لعرض الإجمالي.';return}total.textContent='الإجمالي قبل التأكيد: '+amount+' '+item.currency_code+' (وحدات نقدية صغرى)'};qty.addEventListener('input',updateTotal);updateTotal();const buy=document.createElement('button');buy.textContent='شراء';buy.onclick=async()=>{const quantity=Number(qty.value);if(!Number.isSafeInteger(quantity)||quantity<1||quantity>item.available_quantity){message('الكمية يجب أن تكون عدداً صحيحاً ضمن المخزون المتاح.');return}const keyName='checkout:'+item.offering_id+':'+item.scope_key+':'+(item.location_ref||'');let pending;try{pending=JSON.parse(sessionStorage.getItem(keyName)||'null')}catch{}if(pending&&pending.quantity!==quantity){message('توجد عملية شراء معلقة بكمية مختلفة. أعد المحاولة بالكمية نفسها حتى تتأكد من نتيجتها، ولا تبدأ عملية جديدة قبل حسمها.');return}buy.disabled=true;if(!pending){pending={idempotency_key:crypto.randomUUID(),sale_id:crypto.randomUUID(),quantity};sessionStorage.setItem(keyName,JSON.stringify(pending))}try{const receipt=await api('/api/checkout',{method:'POST',body:JSON.stringify({offering_id:item.offering_id,activity_id:item.activity_id,quantity,scope_key:item.scope_key,location_ref:item.location_ref,idempotency_key:pending.idempotency_key,sale_id:pending.sale_id})});sessionStorage.removeItem(keyName);message('تم البيع بنجاح\nرقم العملية: '+receipt.sale_id+'\nالحالة: '+receipt.state+'\nالكمية: '+receipt.quantity+'\nالإجمالي: '+receipt.total_minor+' '+receipt.currency_code+'\nالمتبقي: '+receipt.quantity_remaining);await loadCatalog();await loadRecentReceipts()}catch(e){message(e.message+'\nيمكن إعادة المحاولة بنفس المفتاح إذا انقطع الاتصال.')}finally{buy.disabled=false}};card.append(title,detail,stock,context,qty,total,buy);root.append(card)}message(data.items.length?'المنتجات المتاحة محدثة من قاعدة البيانات.':'لا توجد حالياً منتجات يمكن إثبات توفرها للبيع.')}catch(e){message(e.message)}}
$('login').onsubmit=async e=>{e.preventDefault();try{const data=await api('/api/login',{method:'POST',body:JSON.stringify(formData(e.target))});message('تم تسجيل الدخول');e.target.reset();await loadMe()}catch(err){message(err.message)}};
$('register').onsubmit=async e=>{e.preventDefault();try{await api('/api/register',{method:'POST',body:JSON.stringify(formData(e.target))});message('تم إنشاء الحساب وتسجيل الدخول');e.target.reset();await loadMe()}catch(err){message(err.message)}};
$('refresh').onclick=loadCatalog;
$('refreshReceipts').onclick=loadRecentReceipts;
$('logout').onclick=async()=>{try{await api('/api/logout',{method:'POST',body:'{}'})}catch{}await loadMe();message('تم تسجيل الخروج')};
loadMe();
</script></body></html>"""


class _ThreadedWSGIServer(ThreadingMixIn, WSGIServer):
    daemon_threads = True


class CommercialWebApplication:
    def __init__(self, database_path=None):
        # APP_DB_PATH is the canonical shared SQLite file for Hospital and Finance.
        # Legacy per-service variables remain fallback-compatible.
        self.database_path = str(
            database_path
            or os.environ.get("APP_DB_PATH")
            or os.environ.get("COMMERCIAL_DB_PATH", "commercial-mvp.sqlite3")
        )
        self.identity = CommercialIdentityApplication()
        self.checkout = CommercialCheckoutApplication()

    @staticmethod
    def _cookies(environ):
        cookie = SimpleCookie()
        cookie.load(environ.get("HTTP_COOKIE", ""))
        morsel = cookie.get("commercial_session")
        return morsel.value if morsel else None

    @staticmethod
    def _json(start_response, status, body, extra_headers=()):
        encoded = json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        headers = [("Content-Type", "application/json; charset=utf-8"),
                   ("Content-Length", str(len(encoded))), ("Cache-Control", "no-store"),
                   ("X-Content-Type-Options", "nosniff"), ("Referrer-Policy", "same-origin")]
        headers.extend(extra_headers)
        start_response(status, headers)
        return [encoded]

    @staticmethod
    def _validate_same_origin(environ):
        """Reject cross-origin state-changing requests when a browser supplies Origin."""
        origin = environ.get("HTTP_ORIGIN")
        if not origin:
            return
        try:
            parsed = urlsplit(origin)
        except ValueError as exc:
            raise ValidationError("Invalid request Origin") from exc
        if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.path or parsed.query or parsed.fragment:
            raise ValidationError("Invalid request Origin")
        request_scheme = environ.get("wsgi.url_scheme", "http").lower()
        request_host = environ.get("HTTP_HOST") or environ.get("SERVER_NAME", "")
        if not environ.get("HTTP_HOST") and environ.get("SERVER_PORT"):
            default_port = "443" if request_scheme == "https" else "80"
            if environ["SERVER_PORT"] != default_port:
                request_host += ":" + environ["SERVER_PORT"]
        if parsed.scheme.lower() != request_scheme or parsed.netloc.lower() != request_host.lower():
            raise ValidationError("Cross-origin request rejected")

    @staticmethod
    def _body(environ):
        try:
            length = int(environ.get("CONTENT_LENGTH") or "0")
        except ValueError as exc:
            raise ValidationError("Invalid request body length") from exc
        if length < 0 or length > 65536:
            raise ValidationError("Request body is too large")
        content_type = environ.get("CONTENT_TYPE", "").split(";", 1)[0].strip().lower()
        if content_type != "application/json":
            raise ValidationError("Content-Type must be application/json")
        raw = environ["wsgi.input"].read(length) if length else b"{}"
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ValidationError("Request body must be valid JSON") from exc
        if not isinstance(body, dict):
            raise ValidationError("Request body must be a JSON object")
        return body

    def __call__(self, environ, start_response):
        method, path = environ.get("REQUEST_METHOD", "GET"), environ.get("PATH_INFO", "/")
        secure_cookie = (
            os.environ.get("COMMERCIAL_COOKIE_SECURE") == "1"
            or environ.get("wsgi.url_scheme", "http").lower() == "https"
        )
        if method == "GET" and path == "/":
            encoded = PAGE.encode("utf-8")
            start_response("200 OK", [("Content-Type", "text/html; charset=utf-8"),
                                      ("Content-Length", str(len(encoded))),
                                      ("Cache-Control", "no-store"),
                                      ("Content-Security-Policy", "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'"),
                                      ("X-Content-Type-Options", "nosniff"),
                                      ("Referrer-Policy", "same-origin")])
            return [encoded]

        db = None
        try:
            if method == "POST":
                self._validate_same_origin(environ)
            db = connect_database(self.database_path)
            token = self._cookies(environ)
            if method == "POST" and path in {"/api/register", "/api/login"}:
                body = self._body(environ)
                if path == "/api/register":
                    self.identity.register(db, username=body.get("username"), password=body.get("password"))
                result = self.identity.login(db, username=body.get("username"), password=body.get("password"))
                secure = "; Secure" if secure_cookie else ""
                cookie = ("commercial_session=" + result["session_token"] +
                          "; Path=/; HttpOnly; SameSite=Lax; Max-Age=28800" + secure)
                return self._json(start_response, "200 OK",
                                  {"person_id": result["person_id"], "username": result["username"],
                                   "expires_at": result["expires_at"]},
                                  [("Set-Cookie", cookie)])

            if method == "POST" and path == "/api/logout":
                self.identity.logout(db, token)
                secure = "; Secure" if secure_cookie else ""
                return self._json(start_response, "200 OK", {"ok": True},
                                  [("Set-Cookie", "commercial_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0" + secure)])

            person_id = self.identity.resolve_session(db, token)
            if person_id is None:
                return self._json(start_response, "401 Unauthorized", {"error": "سجّل الدخول للمتابعة."})

            if method == "GET" and path == "/api/me":
                row = db.execute("SELECT username FROM commercial_credentials WHERE person_id=?", (person_id,)).fetchone()
                if row is None:
                    return self._json(start_response, "401 Unauthorized", {"error": "الحساب غير متاح."})
                return self._json(start_response, "200 OK", {"person_id": person_id, "username": row[0]})

            if method == "GET" and path == "/api/catalog":
                now = __import__("datetime").datetime.now(__import__("datetime").timezone.utc)
                contexts = db.execute(
                    """SELECT DISTINCT activity_id,scope_key,location_ref
                       FROM inventory_positions
                       WHERE state='effective' AND offering_id IS NOT NULL
                       ORDER BY activity_id,scope_key,location_ref"""
                ).fetchall()
                items, seen = [], set()
                for activity_id, scope_key, location_ref in contexts:
                    for item in self.checkout.list_sellable_offerings(
                        db, activity_id=activity_id, scope_key=scope_key,
                        location_ref=location_ref, as_of=now,
                    ):
                        key = (item["offering_id"], item["scope_key"], item["location_ref"])
                        if key not in seen:
                            items.append(item)
                            seen.add(key)
                return self._json(start_response, "200 OK", {"items": items})

            if method == "POST" and path == "/api/checkout":
                body = self._body(environ)
                required = ("offering_id", "activity_id", "quantity", "scope_key",
                            "idempotency_key", "sale_id")
                missing = [key for key in required if key not in body]
                if missing:
                    raise ValidationError("Missing checkout fields: " + ", ".join(missing))
                result = self.checkout.checkout(
                    db, actor_person_id=person_id, activity_id=body["activity_id"],
                    offering_id=body["offering_id"], quantity=body["quantity"],
                    scope_key=body["scope_key"], location_ref=body.get("location_ref"),
                    idempotency_key=body["idempotency_key"], sale_id=body["sale_id"],
                    session_token=token,
                )
                return self._json(start_response, "200 OK", result)

            if method == "GET" and path == "/api/receipts":
                rows = db.execute(
                    """SELECT response_json FROM commercial_checkout_receipts
                       WHERE actor_person_id=? ORDER BY created_at DESC LIMIT 20""", (person_id,),
                ).fetchall()
                return self._json(start_response, "200 OK", {"items": [json.loads(row[0]) for row in rows]})

            if method == "GET" and path.startswith("/api/receipts/"):
                key = path.removeprefix("/api/receipts/")
                row = db.execute(
                    """SELECT response_json FROM commercial_checkout_receipts
                       WHERE actor_person_id=? AND idempotency_key=?""", (person_id, key),
                ).fetchone()
                if row is None:
                    return self._json(start_response, "404 Not Found", {"error": "الإيصال غير موجود."})
                return self._json(start_response, "200 OK", json.loads(row[0]))

            return self._json(start_response, "404 Not Found", {"error": "المسار غير موجود."})
        except ValidationError as exc:
            return self._json(start_response, "400 Bad Request", {"error": str(exc)})
        except sqlite3.IntegrityError:
            return self._json(start_response, "409 Conflict", {"error": "تعارض في البيانات؛ لم تُحفظ العملية."})
        except Exception:
            logging.exception("Unhandled commercial web request failure")
            return self._json(start_response, "500 Internal Server Error", {"error": "حدث خطأ داخلي. لم تُعرض تفاصيل قاعدة البيانات."})
        finally:
            if db is not None:
                db.close()


def main():
    host = os.environ.get("COMMERCIAL_HOST", "127.0.0.1")
    port = int(os.environ.get("COMMERCIAL_PORT", "8080"))
    app = CommercialWebApplication()
    with make_server(host, port, app, server_class=_ThreadedWSGIServer,
                     handler_class=WSGIRequestHandler) as server:
        print(f"Commercial MVP listening on http://{host}:{port}")
        server.serve_forever()


if __name__ == "__main__":
    main()
