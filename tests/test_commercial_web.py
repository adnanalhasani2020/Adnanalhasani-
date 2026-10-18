import io
import json
from datetime import datetime, timedelta, timezone
import uuid
from http.cookies import SimpleCookie

from agent_core.commercial_web import CommercialWebApplication
from agent_core.persistence import connect_database
from agent_core.application import ProductApplication
from agent_core.offering_application import OfferingApplication


def request(app, path, *, method="GET", body=None, cookie=None, scheme="http", origin=None):
    payload = json.dumps(body or {}).encode("utf-8")
    environ = {
        "REQUEST_METHOD": method,
        "PATH_INFO": path,
        "QUERY_STRING": "",
        "CONTENT_LENGTH": str(len(payload) if method == "POST" else 0),
        "CONTENT_TYPE": "application/json",
        "wsgi.input": io.BytesIO(payload if method == "POST" else b""),
        "HTTP_COOKIE": cookie or "",
        "SERVER_NAME": "localhost",
        "SERVER_PORT": "8080",
        "wsgi.url_scheme": scheme,
        **({"HTTP_ORIGIN": origin} if origin is not None else {}),
    }
    captured = {}

    def start_response(status, headers):
        captured["status"] = status
        captured["headers"] = headers

    result = b"".join(app(environ, start_response))
    captured["body"] = json.loads(result) if result and dict(captured["headers"]).get("Content-Type", "").startswith("application/json") else result
    return captured


def test_web_register_login_catalog_and_logout(tmp_path):
    db_path = tmp_path / "commercial-web.sqlite"
    db = connect_database(db_path)
    db.close()
    app = CommercialWebApplication(db_path)

    registered = request(
        app, "/api/register", method="POST",
        body={"username": "buyer_01", "password": "correct horse battery staple"},
    )
    assert registered["status"] == "200 OK"
    cookie_header = dict(registered["headers"])["Set-Cookie"]
    cookie = SimpleCookie()
    cookie.load(cookie_header)
    token = cookie["commercial_session"].value
    request_cookie = "commercial_session=" + token

    me = request(app, "/api/me", cookie=request_cookie)
    assert me["status"] == "200 OK"
    assert me["body"]["username"] == "buyer_01"

    catalog = request(app, "/api/catalog", cookie=request_cookie)
    assert catalog["status"] == "200 OK"
    assert catalog["body"] == {"items": []}

    logged_out = request(app, "/api/logout", method="POST", cookie=request_cookie)
    assert logged_out["status"] == "200 OK"
    rejected = request(app, "/api/me", cookie=request_cookie)
    assert rejected["status"] == "401 Unauthorized"



def test_web_session_can_buy_sellable_product_and_receive_result(tmp_path):
    db_path = tmp_path / "commercial-checkout-web.sqlite"
    db = connect_database(db_path)
    now = datetime.now(timezone.utc)
    stamp = now.isoformat(timespec="microseconds").replace("+00:00", "Z")
    before = (now - timedelta(hours=1)).isoformat(timespec="microseconds").replace("+00:00", "Z")
    uid = lambda: str(uuid.uuid4())

    owner = uid()
    db.execute(
        """INSERT INTO persons(person_id,state,canonical_person_id,created_at,updated_at,version_no)
           VALUES(?,'active',NULL,?,?,1)""", (owner, before, before),
    )
    activity = uid()
    db.execute(
        """INSERT INTO activities(activity_id,owner_person_id,state,created_at,updated_at)
           VALUES(?,?,'active',?,?)""", (activity, owner, before, before),
    )
    products = ProductApplication()
    product = products.create_product(db, "Real bottle", actor_context_ref="web-test", now=before)
    products.transition_product(
        db, product.product.id, "activate", expected_version=1,
        actor_context_ref="web-test", now=before,
    )
    offerings = OfferingApplication()
    offering = offerings.create_offering(
        db, product_id=product.product.id, activity_id=activity,
        effective_from=before, actor_context_ref="web-test", now=before,
    )
    offerings.transition_offering(
        db, offering.offering.id, "activate", expected_version=1,
        actor_context_ref="web-test", now=before,
    )
    position_id = uid()
    db.execute(
        """INSERT INTO inventory_positions(
               inventory_position_id,activity_id,product_id,offering_id,location_ref,
               scope_key,state,quantity_minor,observed_at,effective_from,effective_to,
               created_at,updated_at,provenance_id,version_no
           ) VALUES(?,?,?,?,?,'store-A','effective',5,?,?,NULL,?,?,NULL,1)""",
        (position_id, activity, str(product.product.id), str(offering.offering.id),
         "front", before, before, before, before),
    )
    db.execute(
        """INSERT INTO offering_prices(
               offering_price_id,offering_id,unit_price_minor,currency_code,
               effective_from,effective_to,actor_context_ref,created_at
           ) VALUES(?,?,990,'SAR',?,NULL,'web-test',?)""",
        (uid(), str(offering.offering.id), before, before),
    )
    db.commit()
    db.close()

    app = CommercialWebApplication(db_path)
    registered = request(
        app, "/api/register", method="POST",
        body={"username": "customer_02", "password": "correct horse battery staple"},
    )
    cookie_header = dict(registered["headers"])["Set-Cookie"]
    cookie = SimpleCookie()
    cookie.load(cookie_header)
    session_cookie = "commercial_session=" + cookie["commercial_session"].value

    catalog = request(app, "/api/catalog", cookie=session_cookie)
    assert catalog["status"] == "200 OK"
    assert len(catalog["body"]["items"]) == 1
    item = catalog["body"]["items"][0]
    assert item["available_quantity"] == 5

    checkout = request(
        app, "/api/checkout", method="POST", cookie=session_cookie,
        body={
            "offering_id": item["offering_id"], "activity_id": activity,
            "quantity": 2, "scope_key": "store-A", "location_ref": "front",
            "idempotency_key": "web-checkout-001", "sale_id": uid(),
        },
    )
    assert checkout["status"] == "200 OK"
    assert checkout["body"]["state"] == "fulfilled"
    assert checkout["body"]["total_minor"] == 1980
    assert checkout["body"]["quantity_remaining"] == 3

    # Read the catalog again through the public user flow and prove stock changed.
    refreshed_catalog = request(app, "/api/catalog", cookie=session_cookie)
    assert refreshed_catalog["status"] == "200 OK"
    assert refreshed_catalog["body"]["items"][0]["available_quantity"] == 3

    # Sell the remaining stock; a depleted offering must disappear from the catalog.
    final_checkout = request(
        app, "/api/checkout", method="POST", cookie=session_cookie,
        body={
            "offering_id": item["offering_id"], "activity_id": activity,
            "quantity": 3, "scope_key": "store-A", "location_ref": "front",
            "idempotency_key": "web-checkout-002", "sale_id": uid(),
        },
    )
    assert final_checkout["status"] == "200 OK"
    assert final_checkout["body"]["quantity_remaining"] == 0
    sold_out_catalog = request(app, "/api/catalog", cookie=session_cookie)
    assert sold_out_catalog["status"] == "200 OK"
    assert sold_out_catalog["body"]["items"] == []

    db = connect_database(db_path)
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 2
    assert db.execute("SELECT SUM(quantity_delta) FROM inventory_movements").fetchone()[0] == -5
    db.close()

    # A page refresh can recover committed results from authenticated history.
    history = request(app, "/api/receipts", cookie=session_cookie)
    assert history["status"] == "200 OK"
    assert len(history["body"]["items"]) == 2
    first_receipt = next(item for item in history["body"]["items"] if item["sale_id"] == checkout["body"]["sale_id"])
    assert first_receipt["quantity_remaining"] == 3

    receipt = request(
        app, "/api/receipts/web-checkout-001", cookie=session_cookie,
    )
    assert receipt["status"] == "200 OK"
    assert receipt["body"]["sale_id"] == checkout["body"]["sale_id"]

    printable = request(
        app, "/invoices/" + checkout["body"]["sale_id"], cookie=session_cookie,
    )
    assert printable["status"] == "200 OK"
    printable_html = printable["body"].decode("utf-8")
    assert "إيصال بيع" in printable_html
    assert "ليس فاتورة ضريبية" in printable_html
    assert checkout["body"]["sale_id"] in printable_html
    assert "window.print()" in printable_html

    other = request(
        app, "/api/register", method="POST",
        body={"username": "customer_03", "password": "correct horse battery staple"},
    )
    other_cookie = SimpleCookie()
    other_cookie.load(dict(other["headers"])["Set-Cookie"])
    other_history = request(
        app, "/api/receipts",
        cookie="commercial_session=" + other_cookie["commercial_session"].value,
    )
    assert other_history["status"] == "200 OK"
    assert other_history["body"]["items"] == []

    # Direct receipt lookup must enforce the same account ownership boundary.
    other_receipt = request(
        app, "/api/receipts/web-checkout-001",
        cookie="commercial_session=" + other_cookie["commercial_session"].value,
    )
    assert other_receipt["status"] == "404 Not Found"
    assert "sale_id" not in other_receipt["body"]

    other_invoice = request(
        app, "/invoices/" + checkout["body"]["sale_id"],
        cookie="commercial_session=" + other_cookie["commercial_session"].value,
    )
    assert other_invoice["status"] == "404 Not Found"


def test_storefront_displays_preconfirmation_total_preview(tmp_path):
    app = CommercialWebApplication(tmp_path / "commercial-preview.sqlite")
    page = request(app, "/", method="GET")
    assert page["status"] == "200 OK"
    assert "الإجمالي قبل التأكيد:" in page["body"].decode("utf-8")
    assert "Number.isSafeInteger(amount)" in page["body"].decode("utf-8")



def test_web_session_cookie_is_secure_by_default_on_https(tmp_path, monkeypatch):
    monkeypatch.delenv("COMMERCIAL_COOKIE_SECURE", raising=False)
    db_path = tmp_path / "commercial-web-https.sqlite"
    db = connect_database(db_path)
    db.close()
    app = CommercialWebApplication(db_path)

    registered = request(
        app, "/api/register", method="POST", scheme="https",
        body={"username": "secure_buyer", "password": "correct horse battery staple"},
    )
    assert registered["status"] == "200 OK"
    cookie_header = dict(registered["headers"])["Set-Cookie"]
    assert "; Secure" in cookie_header
    cookie = SimpleCookie()
    cookie.load(cookie_header)
    request_cookie = "commercial_session=" + cookie["commercial_session"].value

    logged_out = request(
        app, "/api/logout", method="POST", cookie=request_cookie, scheme="https",
    )
    assert logged_out["status"] == "200 OK"
    cleared_cookie = dict(logged_out["headers"])["Set-Cookie"]
    assert "Max-Age=0" in cleared_cookie
    assert "; Secure" in cleared_cookie



def test_web_rejects_cross_origin_state_changing_requests(tmp_path):
    db_path = tmp_path / "commercial-web-csrf.sqlite"
    db = connect_database(db_path)
    db.close()
    app = CommercialWebApplication(db_path)

    rejected = request(
        app, "/api/register", method="POST", origin="https://attacker.example",
        body={"username": "csrf_buyer", "password": "correct horse battery staple"},
    )
    assert rejected["status"] == "400 Bad Request"
    assert rejected["body"]["error"] == "Cross-origin request rejected"

    verify = connect_database(db_path)
    assert verify.execute("SELECT COUNT(*) FROM commercial_credentials").fetchone()[0] == 0
    verify.close()


def test_web_accepts_matching_origin_for_registration(tmp_path):
    db_path = tmp_path / "commercial-web-same-origin.sqlite"
    db = connect_database(db_path)
    db.close()
    app = CommercialWebApplication(db_path)

    registered = request(
        app, "/api/register", method="POST", origin="http://localhost:8080",
        body={"username": "same_origin_buyer", "password": "correct horse battery staple"},
    )
    assert registered["status"] == "200 OK"
