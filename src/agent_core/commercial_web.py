"""Dependency-free WSGI interface for the commercial MVP.

Run with: python -m agent_core.commercial_web
Bind to loopback by default. Put a TLS reverse proxy in front before public exposure.
"""
from __future__ import annotations

import html
import json
import logging
import os
import secrets
import sqlite3
from http.cookies import SimpleCookie
from pathlib import Path
from datetime import datetime, timezone
from urllib.parse import parse_qs, urlsplit
from wsgiref.simple_server import WSGIServer, make_server, WSGIRequestHandler
from socketserver import ThreadingMixIn

from agent_core.commerce_checkout import CommercialCheckoutApplication
from agent_core.commercial_identity import CommercialIdentityApplication
from agent_core.commercial_payments import CommercialPaymentsApplication
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
<h2>الديون المستحقة</h2><button id="refreshDebts" type="button">تحديث الديون</button><div id="debts"></div>
<section id="reportSettings" class="card" aria-labelledby="reportSettingsTitle">
<h2 id="reportSettingsTitle">إعدادات طلب تقرير المبيعات</h2>
<p class="muted">اختر التفضيلات التي تريد رفعها للمراجعة. هذه الخيارات تُحفظ على هذا الجهاز فقط، ولا تمنح صلاحية وصول أو تغيّر سياسة الخادم. التقرير يبقى محجوبًا حتى اعتماد الصلاحيات وتطبيقها على الخادم.</p>
<label for="reportAudience">من تقترح أن يُسمح له بمراجعة التقرير؟</label>
<select id="reportAudience"><option value="blocked">إبقاء التقرير محجوبًا حتى الاعتماد</option><option value="finance_review">مستخدم مخوّل بالتقارير المالية/التشغيلية بعد مراجعة الصلاحية</option><option value="activity_manager">مدير النشاط ضمن النطاق المصرّح له</option><option value="organization_manager">مسؤول المؤسسة ضمن نطاق مؤسسته</option></select>
<label for="reportScope">ما نطاق المبيعات المطلوب؟</label>
<select id="reportScope"><option value="authorized_activity">النشاط المصرّح به فقط</option><option value="organization">المؤسسة المصرّح بها فقط</option><option value="all_sales">جميع المبيعات — يتطلب اعتمادًا صريحًا</option></select>
<label for="reportExport">تصدير CSV</label>
<select id="reportExport"><option value="same_permission">نفس صلاحية التقرير ونطاقه</option><option value="separate_permission">صلاحية مستقلة للتصدير، بعد اعتمادها</option></select>
<label for="reportAudit">تدقيق محاولات الوصول</label>
<select id="reportAudit"><option value="every_attempt">تسجيل محاولات السماح والرفض</option><option value="allowed_only">تسجيل الوصول المسموح فقط — يحتاج مراجعة</option></select>
<div class="row"><button id="saveReportSettings" type="button">حفظ اختياراتي على هذا الجهاز</button><button id="resetReportSettings" type="button">إعادة ضبط الاختيارات</button></div>
<p id="reportSettingsStatus" class="muted" role="status" aria-live="polite"></p>
</section>
</section><div id="message" role="status" aria-live="polite"></div>
</main>
<script>
const $=id=>document.getElementById(id);
const message=text=>{$('message').textContent=text};
async function api(path,opts={}){const r=await fetch(path,{credentials:'same-origin',...opts,headers:{'Content-Type':'application/json',...(opts.headers||{})}});let data={};try{data=await r.json()}catch{}if(!r.ok)throw Error(data.error||'تعذر تنفيذ الطلب');return data}
function formData(form){return Object.fromEntries(new FormData(form).entries())}
function reportSettingsKey(personId){return 'commercial-report-settings:'+String(personId)}
function readReportSettings(){return {reportAudience:$('reportAudience').value,reportScope:$('reportScope').value,reportExport:$('reportExport').value,reportAudit:$('reportAudit').value}}
function applyReportSettings(settings){for(const key of ['reportAudience','reportScope','reportExport','reportAudit'])if(typeof settings[key]==='string'&&Array.from($(key).options).some(option=>option.value===settings[key]))$(key).value=settings[key]}
function loadReportSettings(personId){const key=reportSettingsKey(personId);try{const saved=JSON.parse(localStorage.getItem(key)||'null');if(saved&&typeof saved==='object')applyReportSettings(saved);$('reportSettingsStatus').textContent='الاختيارات الحالية تخص هذا المتصفح والحساب فقط؛ لا تغيّر صلاحيات الخادم.'}catch{$('reportSettingsStatus').textContent='تعذر قراءة الاختيارات المحلية؛ يمكنك اختيارها وحفظها من جديد.'} $('saveReportSettings').onclick=()=>{try{localStorage.setItem(key,JSON.stringify(readReportSettings()));$('reportSettingsStatus').textContent='حُفظت اختياراتك محليًا. لم تُرسل للخادم ولم تمنح صلاحية تقرير.'}catch{$('reportSettingsStatus').textContent='تعذر الحفظ المحلي في هذا المتصفح.'}};$('resetReportSettings').onclick=()=>{for(const id of ['reportAudience','reportScope','reportExport','reportAudit'])$(id).selectedIndex=0;try{localStorage.removeItem(key)}catch{}$('reportSettingsStatus').textContent='أُعيد ضبط الاختيارات المحلية. لا تزال صلاحية التقرير محجوبة.'}}
async function loadMe(){try{const me=await api('/api/me');$('auth').classList.add('hidden');$('shop').classList.remove('hidden');$('who').textContent='أهلاً، '+me.username;loadReportSettings(me.person_id);$('catalog').replaceChildren();await loadCatalog();await loadRecentReceipts();await loadDebts()}catch{$('auth').classList.remove('hidden');$('shop').classList.add('hidden')}}
async function loadRecentReceipts(){const root=$('receipts');root.replaceChildren();try{const data=await api('/api/receipts');if(!data.items.length){root.textContent='لا توجد عمليات بيع سابقة لهذا الحساب.';return}for(const receipt of data.items){const card=document.createElement('article');card.className='card';const title=document.createElement('h3');title.textContent='عملية '+receipt.sale_id;const detail=document.createElement('p');detail.textContent='الحالة: '+receipt.state+' — الكمية: '+receipt.quantity+' — الإجمالي: '+receipt.total_minor+' '+receipt.currency_code+' — الدفع: '+(receipt.payment_method||'غير مسجل')+' — المدفوع: '+(receipt.paid_minor??0)+' — المتبقي: '+(receipt.balance_remaining_minor??0);const remaining=document.createElement('p');remaining.textContent='المتبقي عند البيع: '+receipt.quantity_remaining+' — الوقت: '+receipt.occurred_at;const invoice=document.createElement('a');invoice.href='/receipts/'+encodeURIComponent(receipt.sale_id);invoice.target='_blank';invoice.rel='noopener noreferrer';invoice.textContent='عرض إيصال البيع للطباعة';card.append(title,detail,remaining,invoice);root.append(card)}}catch(e){root.textContent=e.message}}
async function loadDebts(){const root=$('debts');root.replaceChildren();try{const data=await api('/api/debts');if(!data.items.length){root.textContent='لا توجد ديون مستحقة لهذا الحساب.';return}for(const debt of data.items){const card=document.createElement('article');card.className='card';const title=document.createElement('h3');title.textContent='بيع '+debt.sale_id;const detail=document.createElement('p');detail.textContent='الإجمالي: '+debt.total_minor+' '+debt.currency_code+' — المدفوع: '+debt.paid_minor+' — المتبقي: '+debt.balance_remaining_minor;const amount=document.createElement('input');amount.type='number';amount.min='1';amount.max=String(debt.balance_remaining_minor);amount.value=String(debt.balance_remaining_minor);amount.setAttribute('aria-label','مبلغ السداد');const pay=document.createElement('button');pay.textContent='تسجيل سداد نقدي';pay.onclick=async()=>{const value=Number(amount.value);if(!Number.isSafeInteger(value)||value<1||value>debt.balance_remaining_minor){message('أدخل مبلغ سداد صحيحاً ضمن الرصيد المستحق.');return}pay.disabled=true;try{const result=await api('/api/payments',{method:'POST',body:JSON.stringify({sale_id:debt.sale_id,amount_minor:value,idempotency_key:crypto.randomUUID()})});message('تم تسجيل السداد النقدي. المتبقي: '+result.balance_remaining_minor+' '+result.currency_code);await loadDebts();await loadRecentReceipts()}catch(e){message(e.message)}finally{pay.disabled=false}};card.append(title,detail,amount,pay);root.append(card)}}catch(e){root.textContent=e.message}}\nasync function loadCatalog(){message('جارٍ قراءة المنتجات والمخزون الفعلي...');try{const data=await api('/api/catalog');const root=$('catalog');root.replaceChildren();for(const item of data.items){const card=document.createElement('article');card.className='card';const title=document.createElement('h3');title.textContent=item.product_name;const detail=document.createElement('p');detail.textContent='السعر: '+item.unit_price_minor+' '+item.currency_code+' (وحدات نقدية صغرى)';const stock=document.createElement('p');stock.textContent='المتاح فعلياً: '+item.available_quantity;const mode=document.createElement('select');mode.setAttribute('aria-label','طريقة البيع');for(const [value,label] of [['cash','نقدي — سداد كامل'],['credit','آجل — رصيد مستحق']]){const option=document.createElement('option');option.value=value;option.textContent=label;mode.append(option)}const paid=document.createElement('input');paid.type='number';paid.min='0';paid.value='0';paid.setAttribute('aria-label','المدفوع عند البيع (للآجل)');paid.placeholder='المبلغ المدفوع الآن (للآجل)';const context=document.createElement('p');context.className='muted';context.textContent='النشاط: '+item.activity_id+' — الموقع: '+(item.location_ref||'غير محدد')+' — النطاق: '+item.scope_key;const qty=document.createElement('input');qty.type='number';qty.min='1';qty.max=String(item.available_quantity);qty.value='1';qty.setAttribute('aria-label','الكمية');const total=document.createElement('p');total.setAttribute('aria-live','polite');const updateTotal=()=>{const count=Number(qty.value);const amount=count*item.unit_price_minor;if(!Number.isSafeInteger(count)||count<1||count>item.available_quantity||!Number.isSafeInteger(amount)||amount<0){total.textContent='أدخل كمية صحيحة ضمن المتاح لعرض الإجمالي.';return}total.textContent='الإجمالي قبل التأكيد: '+amount+' '+item.currency_code+' (وحدات نقدية صغرى)'};qty.addEventListener('input',updateTotal);updateTotal();const buy=document.createElement('button');buy.textContent='شراء';buy.onclick=async()=>{const quantity=Number(qty.value);if(!Number.isSafeInteger(quantity)||quantity<1||quantity>item.available_quantity){message('الكمية يجب أن تكون عدداً صحيحاً ضمن المخزون المتاح.');return}const payment_method=mode.value;const initial_paid_minor=payment_method==='cash'?null:Number(paid.value||0);if(payment_method==='credit'&&(!Number.isSafeInteger(initial_paid_minor)||initial_paid_minor<0)){message('المبلغ المدفوع يجب أن يكون عدداً صحيحاً غير سالب.');return}const keyName='checkout:'+item.offering_id+':'+item.scope_key+':'+(item.location_ref||'');let pending;try{pending=JSON.parse(sessionStorage.getItem(keyName)||'null')}catch{}if(pending&&(pending.quantity!==quantity||pending.payment_method!==payment_method||pending.initial_paid_minor!==initial_paid_minor)){message('توجد عملية شراء معلقة بتفاصيل مختلفة. أعد المحاولة بالتفاصيل نفسها حتى تتأكد من نتيجتها.');return}buy.disabled=true;if(!pending){pending={idempotency_key:crypto.randomUUID(),sale_id:crypto.randomUUID(),quantity,payment_method,initial_paid_minor};sessionStorage.setItem(keyName,JSON.stringify(pending))}try{const receipt=await api('/api/checkout',{method:'POST',body:JSON.stringify({offering_id:item.offering_id,activity_id:item.activity_id,quantity,scope_key:item.scope_key,location_ref:item.location_ref,idempotency_key:pending.idempotency_key,sale_id:pending.sale_id,payment_method:pending.payment_method,...(pending.initial_paid_minor===null?{}:{initial_paid_minor:pending.initial_paid_minor})})});sessionStorage.removeItem(keyName);message('تم البيع بنجاح\nرقم العملية: '+receipt.sale_id+'\nالحالة: '+receipt.state+'\nالكمية: '+receipt.quantity+'\nالإجمالي: '+receipt.total_minor+' '+receipt.currency_code+'\nطريقة الدفع: '+receipt.payment_method+'\nالمدفوع: '+receipt.paid_minor+' '+receipt.currency_code+'\nالدين المتبقي: '+receipt.balance_remaining_minor+' '+receipt.currency_code+'\nالمتبقي بالمخزون: '+receipt.quantity_remaining);await loadCatalog();await loadRecentReceipts();await loadDebts()}catch(e){message(e.message+'\nيمكن إعادة المحاولة بنفس المفتاح إذا انقطع الاتصال.')}finally{buy.disabled=false}};card.append(title,detail,stock,mode,paid,context,qty,total,buy);root.append(card)}message(data.items.length?'المنتجات المتاحة محدثة من قاعدة البيانات.':'لا توجد حالياً منتجات يمكن إثبات توفرها للبيع.')}catch(e){message(e.message)}}
$('login').onsubmit=async e=>{e.preventDefault();try{const data=await api('/api/login',{method:'POST',body:JSON.stringify(formData(e.target))});message('تم تسجيل الدخول');e.target.reset();await loadMe()}catch(err){message(err.message)}};
$('register').onsubmit=async e=>{e.preventDefault();try{await api('/api/register',{method:'POST',body:JSON.stringify(formData(e.target))});message('تم إنشاء الحساب وتسجيل الدخول');e.target.reset();await loadMe()}catch(err){message(err.message)}};
$('refresh').onclick=loadCatalog;
$('refreshReceipts').onclick=loadRecentReceipts;$('refreshDebts').onclick=loadDebts;
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
        self.payments = CommercialPaymentsApplication()

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
                    payment_method=body.get("payment_method", "cash"),
                    initial_paid_minor=body.get("initial_paid_minor"),
                    session_token=token,
                )
                return self._json(start_response, "200 OK", result)

            if method == "GET" and path == "/api/debts":
                return self._json(start_response, "200 OK", {
                    "items": self.payments.list_debts(db, buyer_person_id=person_id)
                })

            if method == "POST" and path == "/api/payments":
                body = self._body(environ)
                required = ("sale_id", "amount_minor", "idempotency_key")
                missing = [key for key in required if key not in body]
                if missing:
                    raise ValidationError("Missing payment fields: " + ", ".join(missing))
                result = self.payments.record_payment(
                    db, buyer_person_id=person_id, sale_id=body["sale_id"],
                    amount_minor=body["amount_minor"], idempotency_key=body["idempotency_key"],
                    now=datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z"),
                )
                return self._json(start_response, "200 OK", result)

            if method == "GET" and (path.startswith("/receipts/") or path.startswith("/invoices/")):
                # /receipts is the truthful route: this document is a sales receipt,
                # not a tax invoice. Keep /invoices as a backward-compatible alias.
                prefix = "/receipts/" if path.startswith("/receipts/") else "/invoices/"
                requested_sale_id = path.removeprefix(prefix).strip()
                if not requested_sale_id or "/" in requested_sale_id:
                    return self._json(start_response, "404 Not Found", {"error": "إيصال البيع غير موجود."})
                rows = db.execute(
                    """SELECT response_json FROM commercial_checkout_receipts
                       WHERE actor_person_id=? ORDER BY created_at DESC""", (person_id,),
                ).fetchall()
                receipt = None
                for row in rows:
                    try:
                        candidate = json.loads(row[0])
                    except (TypeError, json.JSONDecodeError):
                        continue
                    if candidate.get("sale_id") == requested_sale_id:
                        receipt = candidate
                        break
                if receipt is None:
                    return self._json(start_response, "404 Not Found", {"error": "إيصال البيع غير موجود."})
                esc = lambda value: html.escape(str(value), quote=True)
                amount = f"{receipt['total_minor']:,} {receipt['currency_code']}"
                unit = f"{receipt['unit_price_minor']:,} {receipt['currency_code']}"
                page = f"""<!doctype html>
<html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>إيصال بيع {esc(receipt['sale_id'])}</title>
<style>
body{{font-family:system-ui,sans-serif;max-width:760px;margin:2rem auto;padding:1rem;color:#172033}}
header,section,footer{{border:1px solid #d8dee8;border-radius:10px;padding:1rem;margin:.8rem 0}}
h1{{margin:.2rem 0}}.muted{{color:#596579}}.total{{font-size:1.3rem;font-weight:700}}
button{{font:inherit;padding:.6rem 1rem;cursor:pointer}}dt{{font-weight:700}}dd{{margin:0 0 .7rem}}
@media print{{button{{display:none}}body{{margin:0;max-width:none}}header,section,footer{{break-inside:avoid}}}}
</style></head><body>
<header><h1>إيصال بيع</h1><p class="muted">إثبات عملية بيع مسجلة في النظام — ليس فاتورة ضريبية.</p></header>
<section><dl>
<dt>رقم البيع</dt><dd>{esc(receipt['sale_id'])}</dd>
<dt>تاريخ العملية (UTC)</dt><dd>{esc(receipt['occurred_at'])}</dd>
<dt>الحالة</dt><dd>{esc(receipt['state'])}</dd>
<dt>المنتج</dt><dd>{esc(receipt.get('product_name') or 'اسم المنتج غير محفوظ في الإيصالات القديمة')}</dd>
<dt>معرّف المنتج</dt><dd>{esc(receipt['product_id'])}</dd>
<dt>معرّف العرض</dt><dd>{esc(receipt['offering_id'])}</dd>
<dt>الكمية</dt><dd>{esc(receipt['quantity'])}</dd>
<dt>سعر الوحدة</dt><dd>{esc(unit)}</dd>
<dt>طريقة الدفع</dt><dd>{esc(receipt.get('payment_method', 'غير مسجل'))}</dd>
<dt>المبلغ المدفوع</dt><dd>{esc(f"{receipt.get('paid_minor', 0):,} {receipt['currency_code']}")}</dd>
<dt>الرصيد المستحق</dt><dd>{esc(f"{receipt.get('balance_remaining_minor', 0):,} {receipt['currency_code']}")}</dd>
<dt>الموقع</dt><dd>{esc(receipt.get('location_ref') or 'غير محدد')}</dd>
<dt>النطاق</dt><dd>{esc(receipt['scope_key'])}</dd>
</dl><p class="total">الإجمالي: {esc(amount)}</p></section>
<footer><p>المخزون المتبقي بعد البيع: {esc(receipt['quantity_remaining'])}</p>
<button type="button" onclick="window.print()">طباعة / حفظ PDF</button></footer>
</body></html>"""
                encoded = page.encode("utf-8")
                start_response("200 OK", [
                    ("Content-Type", "text/html; charset=utf-8"),
                    ("Content-Length", str(len(encoded))),
                    ("Cache-Control", "no-store"),
                    ("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'"),
                    ("X-Content-Type-Options", "nosniff"),
                    ("Referrer-Policy", "same-origin"),
                ])
                return [encoded]

            if method == "GET" and path == "/api/reports/sales":
                # A commercial session establishes identity only, not report authority.
                # Keep JSON and CSV blocked until an owner-approved, server-enforced
                # audience and data-scope policy exists.
                return self._json(start_response, "403 Forbidden", {
                    "error": "الوصول إلى تقرير المبيعات محجوب حتى اعتماد صلاحيات التقرير وتطبيقها على الخادم."
                })
            if method == "GET" and path == "/api/receipts":
                rows = db.execute(
                    """SELECT response_json FROM commercial_checkout_receipts
                       WHERE actor_person_id=? ORDER BY created_at DESC LIMIT 20""", (person_id,),
                ).fetchall()
                items = []
                for row in rows:
                    try:
                        receipt = json.loads(row[0])
                    except (TypeError, json.JSONDecodeError):
                        # A single damaged legacy row must not take down the
                        # customer's entire receipt history. Avoid logging payloads.
                        logging.warning("Skipping malformed persisted commercial receipt")
                        continue
                    if isinstance(receipt, dict):
                        items.append(receipt)
                return self._json(start_response, "200 OK", {"items": items})

            if method == "GET" and path.startswith("/api/receipts/"):
                key = path.removeprefix("/api/receipts/")
                row = db.execute(
                    """SELECT response_json FROM commercial_checkout_receipts
                       WHERE actor_person_id=? AND idempotency_key=?""", (person_id, key),
                ).fetchone()
                if row is None:
                    return self._json(start_response, "404 Not Found", {"error": "الإيصال غير موجود."})
                try:
                    receipt = json.loads(row[0])
                except (TypeError, json.JSONDecodeError):
                    logging.warning("Skipping malformed persisted commercial receipt lookup")
                    return self._json(start_response, "404 Not Found", {"error": "الإيصال غير موجود."})
                if not isinstance(receipt, dict):
                    return self._json(start_response, "404 Not Found", {"error": "الإيصال غير موجود."})
                return self._json(start_response, "200 OK", receipt)

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
