import io
import json
from datetime import datetime, timedelta, timezone
import uuid
from urllib.parse import urlsplit
from http.cookies import SimpleCookie

from agent_core.commercial_web import CommercialWebApplication
from agent_core.persistence import connect_database
from agent_core.application import ProductApplication
from agent_core.offering_application import OfferingApplication


def request(app, path, *, method="GET", body=None, cookie=None):
    parsed_path = urlsplit(path)
    payload = json.dumps(body or {}).encode("utf-8")
    environ = {
        "REQUEST_METHOD": method,
        "PATH_INFO": parsed_path.path,
        "QUERY_STRING": parsed_path.query,
        "CONTENT_LENGTH": str(len(payload) if method == "POST" else 0),
        "CONTENT_TYPE": "application/json",
        "wsgi.input": io.BytesIO(payload if method == "POST" else b""),
        "HTTP_COOKIE": cookie or "",
        "SERVER_NAME": "localhost",
        "SERVER_PORT": "8080",
        "wsgi.url_scheme": "http",
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

    # Identity alone is not report authorization. The current commercial identity
    # model has no approved report permission, so JSON and CSV must fail closed.
    unauthenticated_report = request(app, "/api/reports/sales")
    assert unauthenticated_report["status"] == "401 Unauthorized"
    unauthenticated_csv = request(app, "/api/reports/sales?format=csv")
    assert unauthenticated_csv["status"] == "401 Unauthorized"
    assert "text/csv" not in dict(unauthenticated_csv["headers"]).get("Content-Type", "")
    unauth_csv_body = (
        unauthenticated_csv["body"].decode("utf-8", errors="replace")
        if isinstance(unauthenticated_csv["body"], bytes)
        else json.dumps(unauthenticated_csv["body"], ensure_ascii=False)
    )
    assert not any(marker in unauth_csv_body.lower() for marker in ("sale_id", "total_minor", "customer", "csv"))
    assert "items" not in unauthenticated_csv["body"]

    sales_before = connect_database(db_path)
    before_count = sales_before.execute("SELECT COUNT(*) FROM sales").fetchone()[0]
    sales_before.close()

    blocked_json = request(
        app, "/api/reports/sales?from=" + now.date().isoformat(),
        cookie=session_cookie,
    )
    assert blocked_json["status"] == "403 Forbidden"
    assert blocked_json["body"] == {"error": "الوصول إلى تقرير المبيعات محجوب حتى اعتماد صلاحيات التقرير وتطبيقها على الخادم."}
    assert "items" not in blocked_json["body"]
    assert "total_sales" not in blocked_json["body"]
    assert str(checkout["body"]["sale_id"]) not in json.dumps(blocked_json["body"], ensure_ascii=False)
    assert str(checkout["body"]["total_minor"]) not in json.dumps(blocked_json["body"], ensure_ascii=False)

    blocked_csv = request(
        app, "/api/reports/sales?format=csv&from=" + now.date().isoformat(),
        cookie=session_cookie,
    )
    assert blocked_csv["status"] == "403 Forbidden"
    assert blocked_csv["body"]["error"] == blocked_json["body"]["error"]
    assert "text/csv" not in dict(blocked_csv["headers"]).get("Content-Type", "")
    blocked_csv_body = (
        blocked_csv["body"].decode("utf-8", errors="replace")
        if isinstance(blocked_csv["body"], bytes)
        else json.dumps(blocked_csv["body"], ensure_ascii=False)
    )
    assert str(checkout["body"]["sale_id"]) not in blocked_csv_body
    assert str(checkout["body"]["total_minor"]) not in blocked_csv_body
    assert "sale_id" not in blocked_csv_body.lower()
    assert "total_minor" not in blocked_csv_body.lower()
    assert "1980" not in blocked_csv_body

    # Direct API calls and arbitrary report query parameters cannot bypass the block.
    for query in ("", "?format=csv", "?limit=1&offset=0", "?from=invalid"):
        denied = request(app, "/api/reports/sales" + query, cookie=session_cookie)
        assert denied["status"] == "403 Forbidden", query
        assert "items" not in denied["body"]

    sales_after = connect_database(db_path)
    after_count = sales_after.execute("SELECT COUNT(*) FROM sales").fetchone()[0]
    sales_after.close()
    assert after_count == before_count

    receipt = request(
        app, "/api/receipts/web-checkout-001", cookie=session_cookie,
    )
    assert receipt["status"] == "200 OK"
    assert receipt["body"]["sale_id"] == checkout["body"]["sale_id"]

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


def test_storefront_displays_preconfirmation_total_preview(tmp_path):
    app = CommercialWebApplication(tmp_path / "commercial-preview.sqlite")
    page = request(app, "/", method="GET")
    assert page["status"] == "200 OK"
    assert "الإجمالي قبل التأكيد:" in page["body"].decode("utf-8")
    assert "Number.isSafeInteger(amount)" in page["body"].decode("utf-8")
