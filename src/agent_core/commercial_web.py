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
from urllib.parse import parse_qs, urlsplit
from wsgiref.simple_server import WSGIServer, make_server, WSGIRequestHandler
from socketserver import ThreadingMixIn

from agent_core.commerce_checkout import CommercialCheckoutApplication
from agent_core.commercial_identity import CommercialIdentityApplication
from agent_core.persistence import connect_database
from agent_core.shared import ValidationError
from agent_core.supabase_rpc import SupabaseAuthClient, SupabaseRPCClient, SupabaseRPCError


PAGE = r"""<!doctype html>
<html lang="ar" dir="rtl">
<head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>المتجر — النسخة التجارية الأولى</title>
<style>
:root{color-scheme:light;--ink:#15243b;--muted:#66758b;--line:#dce4ef;--brand:#2458a6;--brand-dark:#173b73;--wash:#f3f6fb;--good:#16794b}*{box-sizing:border-box}body{font-family:system-ui,-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;margin:0;min-height:100vh;background:radial-gradient(ellipse at top right,#e3edff 0,transparent 48%),var(--wash);color:var(--ink)}button,input,select{font:inherit}button{cursor:pointer;border:0;border-radius:12px;background:var(--brand);color:#fff;padding:.82rem 1.1rem;font-weight:650;transition:background .15s,transform .15s}button:hover{background:var(--brand-dark)}button:active{transform:translateY(1px)}button:focus-visible,input:focus-visible,select:focus-visible{outline:3px solid #92b7f8;outline-offset:2px}button:disabled{opacity:.55;cursor:wait}input,select{width:100%;min-width:0;border:1px solid #cbd6e5;border-radius:11px;padding:.82rem .9rem;background:#fff;color:var(--ink)}label{display:block;font-weight:650;margin:.75rem 0 .35rem}.shell{width:min(1120px,100%);margin:0 auto;padding:clamp(1rem,4vw,3rem)}.topline{display:flex;align-items:center;gap:.8rem;margin-bottom:1.4rem}.brandmark{display:grid;place-items:center;width:46px;height:46px;border-radius:15px;background:var(--brand);color:white;font-size:1.35rem;font-weight:800}.brandname{font-weight:800;font-size:1.15rem;letter-spacing:-.02em}.brandhint{font-size:.85rem;color:var(--muted)}main{background:#fff;padding:clamp(1.2rem,4vw,2.4rem);border:1px solid #e7edf5;border-radius:24px;box-shadow:0 22px 65px #1a35540e}.auth-layout{display:grid;grid-template-columns:minmax(0,1fr) minmax(290px,.82fr);gap:clamp(1.5rem,5vw,4rem);align-items:center;min-height:480px}.auth-copy{padding:1rem 0}.eyebrow{display:inline-flex;align-items:center;gap:.5rem;color:var(--brand);font-weight:750;font-size:.88rem;background:#edf4ff;padding:.45rem .75rem;border-radius:999px}.auth-copy h1{font-size:clamp(2rem,5vw,3.25rem);line-height:1.15;letter-spacing:-.045em;margin:1.1rem 0}.auth-copy p{color:var(--muted);font-size:1.04rem;line-height:1.9;max-width:42ch}.trust-note{display:flex;gap:.65rem;align-items:flex-start;color:var(--muted);font-size:.86rem;line-height:1.7;margin-top:2rem}.auth-panel{border:1px solid var(--line);border-radius:20px;padding:clamp(1rem,3vw,1.6rem);background:linear-gradient(180deg,#fbfdff,#fff)}.auth-panel h2{margin:.15rem 0 .4rem;font-size:1.4rem}.muted{color:var(--muted)}.auth-tabs{display:grid;grid-template-columns:1fr 1fr;gap:.35rem;padding:.3rem;background:#edf2f8;border-radius:13px;margin:1.2rem 0}.auth-tabs button{background:transparent;color:var(--muted);padding:.65rem}.auth-tabs button[aria-selected=true]{background:#fff;color:var(--brand-dark);box-shadow:0 2px 8px #173b7310}.auth-form{display:grid;gap:.8rem}.auth-form .primary{width:100%;margin-top:.35rem}.field-hint{font-size:.8rem;color:var(--muted);margin:.3rem 0 0}.form-error{color:#a62d36;background:#fff1f1;border:1px solid #ffd9dc;border-radius:10px;padding:.7rem;font-size:.9rem}.form-success{color:var(--good)}.row{display:flex;gap:.6rem;flex-wrap:wrap;margin:.7rem 0}.card{border:1px solid var(--line);border-radius:14px;padding:1rem;margin:.7rem 0}.hidden{display:none!important}#message{white-space:pre-wrap;padding:.6rem}#catalog{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:.8rem}@media(max-width:760px){.shell{padding:1rem}.auth-layout{grid-template-columns:1fr;gap:1rem;min-height:0}.auth-copy{padding:.4rem 0}.auth-copy h1{font-size:2.15rem}.auth-copy p{font-size:.98rem}.trust-note{margin-top:1rem}.auth-panel{padding:1.1rem}main{border-radius:18px}}
</style></head>
<body><div class="shell">
<div class="topline"><div class="brandmark" aria-hidden="true">ر</div><div><div class="brandname">رُوح <span class="muted">| التجارة</span></div><div class="brandhint">مساحة بسيطة لإدارة البيع اليومي</div></div></div>
<main>
<section id="auth" class="auth-layout" aria-labelledby="welcomeTitle">
<div class="auth-copy">
<span class="eyebrow">● بداية واضحة، بيع موثوق</span>
<h1 id="welcomeTitle">كل يوم يبدأ<br>بخطوة أسهل.</h1>
<p>ادخل إلى مساحتك التجارية لمتابعة المنتجات المتاحة وإتمام عمليات البيع بوضوح، من السعر قبل التأكيد إلى إيصال العملية بعدها.</p>
<div class="trust-note"><span aria-hidden="true">◈</span><span>حسابك محمي بجلسة محدودة المدة. لا نعرض منتجات للبيع ما لم يكن توفر المخزون والسعر قابلين للتحقق.</span></div>
</div>
<div class="auth-panel">
<h2 id="authTitle">مرحبًا بعودتك</h2><p id="authSubtitle" class="muted">سجّل الدخول للمتابعة إلى متجرك.</p>
<div class="auth-tabs" role="tablist" aria-label="نوع الحساب"><button type="button" id="loginTab" role="tab" aria-selected="true" aria-controls="login">تسجيل الدخول</button><button type="button" id="registerTab" role="tab" aria-selected="false" aria-controls="register">حساب جديد</button></div>
<form id="login" class="auth-form">
<label for="loginUsername">اسم المستخدم</label><input id="loginUsername" name="username" autocomplete="username" autocapitalize="none" spellcheck="false" placeholder="مثال: ahmed" required minlength="3" maxlength="80">
<label for="loginPassword">كلمة المرور</label><input id="loginPassword" name="password" type="password" autocomplete="current-password" placeholder="أدخل كلمة المرور" required minlength="12" maxlength="1024">
<p class="field-hint">استخدم كلمة المرور التي أنشأت بها حسابك.</p>
<button class="primary" type="submit">دخول إلى حسابي <span aria-hidden="true">←</span></button>
</form>
<form id="register" class="auth-form hidden">
<label for="registerUsername">اسم المستخدم</label><input id="registerUsername" name="username" autocomplete="username" autocapitalize="none" spellcheck="false" placeholder="اختر اسم مستخدم" required minlength="3" maxlength="80" pattern="[A-Za-z0-9._-]{3,80}" title="3 إلى 80 حرفًا إنجليزيًا أو رقمًا أو نقطة أو شرطة">
<label for="registerPassword">كلمة المرور</label><input id="registerPassword" name="password" type="password" autocomplete="new-password" placeholder="12 حرفًا على الأقل" required minlength="12" maxlength="1024">
<p class="field-hint">استخدم 12 حرفًا على الأقل. لا تستخدم كلمة مرور حساب آخر.</p>
<button class="primary" type="submit">إنشاء الحساب والمتابعة <span aria-hidden="true">←</span></button>
</form>
<p id="authFeedback" class="muted" role="status" aria-live="polite"></p>
</div>
</section>
<section id="shop" class="hidden">
<div class="row"><strong id="who"></strong><button id="refresh">تحديث المنتجات</button><button id="logout">تسجيل الخروج</button></div>
<div id="catalog"></div>
<h2>عملياتي الأخيرة</h2><button id="refreshReceipts" type="button">تحديث سجل المبيعات</button><div id="receipts"></div>
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
</main></div>
<script>
const $=id=>document.getElementById(id);
const message=text=>{$('message').textContent=text};
async function api(path,opts={}){const r=await fetch(path,{credentials:'same-origin',...opts,headers:{'Content-Type':'application/json',...(opts.headers||{})}});let data={};try{data=await r.json()}catch{}if(!r.ok)throw Error(data.error||'تعذر تنفيذ الطلب');return data}
function formData(form){return Object.fromEntries(new FormData(form).entries())}
function setAuthMode(mode){const registering=mode==='register';$('login').classList.toggle('hidden',registering);$('register').classList.toggle('hidden',!registering);$('loginTab').setAttribute('aria-selected',String(!registering));$('registerTab').setAttribute('aria-selected',String(registering));$('authTitle').textContent=registering?'أنشئ حسابك':'مرحبًا بعودتك';$('authSubtitle').textContent=registering?'أنشئ حسابًا محليًا للبدء.':'سجّل الدخول للمتابعة إلى متجرك.';$('authFeedback').textContent='';message('');(registering?$('registerUsername'):$('loginUsername')).focus()}
$('loginTab').addEventListener('click',()=>setAuthMode('login'));$('registerTab').addEventListener('click',()=>setAuthMode('register'));
function reportSettingsKey(personId){return 'commercial-report-settings:'+String(personId)}
function readReportSettings(){return {reportAudience:$('reportAudience').value,reportScope:$('reportScope').value,reportExport:$('reportExport').value,reportAudit:$('reportAudit').value}}
function applyReportSettings(settings){for(const key of ['reportAudience','reportScope','reportExport','reportAudit'])if(typeof settings[key]==='string'&&Array.from($(key).options).some(option=>option.value===settings[key]))$(key).value=settings[key]}
function loadReportSettings(personId){const key=reportSettingsKey(personId);try{const saved=JSON.parse(localStorage.getItem(key)||'null');if(saved&&typeof saved==='object')applyReportSettings(saved);$('reportSettingsStatus').textContent='الاختيارات الحالية تخص هذا المتصفح والحساب فقط؛ لا تغيّر صلاحيات الخادم.'}catch{$('reportSettingsStatus').textContent='تعذر قراءة الاختيارات المحلية؛ يمكنك اختيارها وحفظها من جديد.'} $('saveReportSettings').onclick=()=>{try{localStorage.setItem(key,JSON.stringify(readReportSettings()));$('reportSettingsStatus').textContent='حُفظت اختياراتك محليًا. لم تُرسل للخادم ولم تمنح صلاحية تقرير.'}catch{$('reportSettingsStatus').textContent='تعذر الحفظ المحلي في هذا المتصفح.'}};$('resetReportSettings').onclick=()=>{for(const id of ['reportAudience','reportScope','reportExport','reportAudit'])$(id).selectedIndex=0;try{localStorage.removeItem(key)}catch{}$('reportSettingsStatus').textContent='أُعيد ضبط الاختيارات المحلية. لا تزال صلاحية التقرير محجوبة.'}}
async function loadMe(){try{const me=await api('/api/me');$('auth').classList.add('hidden');$('shop').classList.remove('hidden');$('who').textContent='أهلاً، '+me.username;loadReportSettings(me.person_id);$('catalog').replaceChildren();await loadCatalog();await loadRecentReceipts()}catch{$('auth').classList.remove('hidden');$('shop').classList.add('hidden')}}
async function loadRecentReceipts(){const root=$('receipts');root.replaceChildren();try{const data=await api('/api/receipts');if(!data.items.length){root.textContent='لا توجد عمليات بيع سابقة لهذا الحساب.';return}for(const receipt of data.items){const card=document.createElement('article');card.className='card';const title=document.createElement('h3');title.textContent='عملية '+receipt.sale_id;const detail=document.createElement('p');detail.textContent='الحالة: '+receipt.state+' — الكمية: '+receipt.quantity+' — الإجمالي: '+receipt.total_minor+' '+receipt.currency_code;const remaining=document.createElement('p');remaining.textContent='المتبقي عند البيع: '+receipt.quantity_remaining+' — الوقت: '+receipt.occurred_at;const invoice=document.createElement('a');invoice.href='/receipts/'+encodeURIComponent(receipt.sale_id);invoice.target='_blank';invoice.rel='noopener noreferrer';invoice.textContent='عرض إيصال البيع للطباعة';card.append(title,detail,remaining,invoice);root.append(card)}}catch(e){root.textContent=e.message}}
async function loadCatalog(){message('جارٍ قراءة المنتجات والمخزون الفعلي...');try{const data=await api('/api/catalog');const root=$('catalog');root.replaceChildren();for(const item of data.items){const card=document.createElement('article');card.className='card';const title=document.createElement('h3');title.textContent=item.product_name;const detail=document.createElement('p');detail.textContent='السعر: '+item.unit_price_minor+' '+item.currency_code+' (وحدات نقدية صغرى)';const stock=document.createElement('p');stock.textContent='المتاح فعلياً: '+item.available_quantity;const context=document.createElement('p');context.className='muted';context.textContent='النشاط: '+item.activity_id+' — الموقع: '+(item.location_ref||'غير محدد')+' — النطاق: '+item.scope_key;const qty=document.createElement('input');qty.type='number';qty.min='1';qty.max=String(item.available_quantity);qty.value='1';qty.setAttribute('aria-label','الكمية');const total=document.createElement('p');total.setAttribute('aria-live','polite');const updateTotal=()=>{const count=Number(qty.value);const amount=count*item.unit_price_minor;if(!Number.isSafeInteger(count)||count<1||count>item.available_quantity||!Number.isSafeInteger(amount)||amount<0){total.textContent='أدخل كمية صحيحة ضمن المتاح لعرض الإجمالي.';return}total.textContent='الإجمالي قبل التأكيد: '+amount+' '+item.currency_code+' (وحدات نقدية صغرى)'};qty.addEventListener('input',updateTotal);updateTotal();const buy=document.createElement('button');buy.textContent='شراء';buy.onclick=async()=>{const quantity=Number(qty.value);if(!Number.isSafeInteger(quantity)||quantity<1||quantity>item.available_quantity){message('الكمية يجب أن تكون عدداً صحيحاً ضمن المخزون المتاح.');return}const keyName='checkout:'+item.offering_id+':'+item.scope_key+':'+(item.location_ref||'');let pending;try{pending=JSON.parse(sessionStorage.getItem(keyName)||'null')}catch{}if(pending&&pending.quantity!==quantity){message('توجد عملية شراء معلقة بكمية مختلفة. أعد المحاولة بالكمية نفسها حتى تتأكد من نتيجتها، ولا تبدأ عملية جديدة قبل حسمها.');return}buy.disabled=true;if(!pending){pending={idempotency_key:crypto.randomUUID(),sale_id:crypto.randomUUID(),quantity};sessionStorage.setItem(keyName,JSON.stringify(pending))}try{const receipt=await api('/api/checkout',{method:'POST',body:JSON.stringify({offering_id:item.offering_id,activity_id:item.activity_id,quantity,scope_key:item.scope_key,location_ref:item.location_ref,idempotency_key:pending.idempotency_key,sale_id:pending.sale_id})});sessionStorage.removeItem(keyName);message('تم البيع بنجاح\nرقم العملية: '+receipt.sale_id+'\nالحالة: '+receipt.state+'\nالكمية: '+receipt.quantity+'\nالإجمالي: '+receipt.total_minor+' '+receipt.currency_code+'\nالمتبقي: '+receipt.quantity_remaining);await loadCatalog();await loadRecentReceipts()}catch(e){message(e.message+'\nيمكن إعادة المحاولة بنفس المفتاح إذا انقطع الاتصال.')}finally{buy.disabled=false}};card.append(title,detail,stock,context,qty,total,buy);root.append(card)}message(data.items.length?'المنتجات المتاحة محدثة من قاعدة البيانات.':'لا توجد حالياً منتجات يمكن إثبات توفرها للبيع.')}catch(e){message(e.message)}}
$('login').onsubmit=async e=>{e.preventDefault();try{const data=await api('/api/login',{method:'POST',body:JSON.stringify(formData(e.target))});$('authFeedback').textContent='تم تسجيل الدخول بنجاح.';$('authFeedback').className='muted form-success';e.target.reset();await loadMe()}catch(err){$('authFeedback').textContent=err.message;$('authFeedback').className='form-error'}};
$('register').onsubmit=async e=>{e.preventDefault();try{const data=await api('/api/register',{method:'POST',body:JSON.stringify(formData(e.target))});e.target.reset();if(data.needs_email_confirmation){setAuthMode('login');$('authFeedback').textContent=data.message||'تحقق من بريدك الإلكتروني لتأكيد الحساب ثم سجّل الدخول.';$('authFeedback').className='muted form-success';return}$('authFeedback').textContent='تم إنشاء الحساب وتسجيل الدخول.';$('authFeedback').className='muted form-success';await loadMe()}catch(err){$('authFeedback').textContent=err.message;$('authFeedback').className='form-error'}};
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

    @staticmethod
    def _supabase_auth_enabled() -> bool:
        return (os.environ.get("SUPABASE_URL", "").startswith("https://")
                and bool(os.environ.get("SUPABASE_ANON_KEY", "")))

    def __call__(self, environ, start_response):
        method, path = environ.get("REQUEST_METHOD", "GET"), environ.get("PATH_INFO", "/")
        secure_cookie = (
            os.environ.get("COMMERCIAL_COOKIE_SECURE") == "1"
            or environ.get("wsgi.url_scheme", "http").lower() == "https"
        )
        if method == "GET" and path == "/":
            page = PAGE
            if self._supabase_auth_enabled():
                page = page.replace(
                    '<label for="loginUsername">اسم المستخدم</label><input id="loginUsername" name="username" autocomplete="username" autocapitalize="none" spellcheck="false" placeholder="مثال: ahmed" required minlength="3" maxlength="80">',
                    '<label for="loginEmail">البريد الإلكتروني</label><input id="loginEmail" name="email" type="email" autocomplete="email" autocapitalize="none" spellcheck="false" placeholder="name@example.com" required maxlength="320">'
                )
                page = page.replace(
                    '<label for="registerUsername">اسم المستخدم</label><input id="registerUsername" name="username" autocomplete="username" autocapitalize="none" spellcheck="false" placeholder="اختر اسم مستخدم" required minlength="3" maxlength="80" pattern="[A-Za-z0-9._-]{3,80}" title="3 إلى 80 حرفًا إنجليزيًا أو رقمًا أو نقطة أو شرطة">',
                    '<label for="registerEmail">البريد الإلكتروني</label><input id="registerEmail" name="email" type="email" autocomplete="email" autocapitalize="none" spellcheck="false" placeholder="name@example.com" required maxlength="320">'
                )
                page = page.replace("$('loginUsername')", "$('loginEmail')").replace("$('registerUsername')", "$('registerEmail')")
                page = page.replace("أنشئ حسابًا محليًا للبدء.", "أنشئ حسابك باستخدام البريد الإلكتروني.")
                page = page.replace("استخدم كلمة المرور التي أنشأت بها حسابك.", "أدخل كلمة مرور حسابك في Supabase.")
                page = page.replace("استخدم 12 حرفًا على الأقل. لا تستخدم كلمة مرور حساب آخر.", "يجب أن تتكون كلمة المرور من 12 حرفًا على الأقل.")
            encoded = page.encode("utf-8")
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

            # Supabase onboarding is a separate authenticated path. It deliberately
            # does not reuse the local SQLite session or SQLite person identifier.
            if method == "POST" and path == "/api/supabase/organizations":
                authorization = environ.get("HTTP_AUTHORIZATION", "")
                scheme, separator, access_token = authorization.partition(" ")
                if not separator or scheme.lower() != "bearer" or not access_token.strip():
                    return self._json(start_response, "401 Unauthorized", {
                        "error": "أرسل رمز وصول صالحًا لجلسة Supabase في ترويسة Authorization."
                    })
                body = self._body(environ)
                client = SupabaseRPCClient(access_token.strip())
                organization_id = client.create_organization(
                    name=body.get("name"),
                    slug=body.get("slug"),
                    organization_type=body.get("organization_type"),
                    currency_code=body.get("currency_code"),
                    timezone=body.get("timezone"),
                    locale=body.get("locale"),
                    country_code=body.get("country_code"),
                    module_keys=body.get("module_keys"),
                )
                return self._json(start_response, "201 Created", {
                    "organization_id": organization_id
                })

            db = connect_database(self.database_path)
            token = self._cookies(environ)
            if method == "POST" and path in {"/api/register", "/api/login"}:
                body = self._body(environ)
                if self._supabase_auth_enabled():
                    auth = SupabaseAuthClient()
                    if path == "/api/register":
                        auth_result = auth.sign_up(email=body.get("email"), password=body.get("password"))
                        # Supabase may require email confirmation before it issues a session.
                        if not auth_result.get("access_token"):
                            return self._json(start_response, "202 Accepted", {
                                "needs_email_confirmation": True,
                                "message": "تم إرسال طلب إنشاء الحساب. تحقق من بريدك الإلكتروني لتأكيد الحساب، ثم سجّل الدخول."
                            })
                    else:
                        auth_result = auth.sign_in_with_password(
                            email=body.get("email"), password=body.get("password")
                        )
                    user = auth_result.get("user")
                    if not isinstance(user, dict) or not user.get("id") or not auth_result.get("access_token"):
                        return self._json(start_response, "502 Bad Gateway", {
                            "error": "لم تُرجع خدمة Supabase جلسة مستخدم صالحة."
                        })
                    result = self.identity.login_external(
                        db, provider_user_id=str(user["id"]),
                        email=user.get("email") or body.get("email"),
                    )
                else:
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
                row = db.execute(
                    """SELECT COALESCE(i.email,c.username)
                       FROM commercial_credentials c
                       LEFT JOIN commercial_supabase_identities i ON i.person_id=c.person_id
                       WHERE c.person_id=?""", (person_id,),
                ).fetchone()
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
        except SupabaseRPCError as exc:
            logging.warning("Supabase organization onboarding request failed: %s", str(exc))
            return self._json(start_response, "502 Bad Gateway", {"error": str(exc)})
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
