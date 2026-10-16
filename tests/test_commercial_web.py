import io
import json
from datetime import datetime, timedelta, timezone
import uuid
from http.cookies import SimpleCookie

from agent_core.commercial_web import CommercialWebApplication
from agent_core.persistence import connect_database
from agent_core.application import ProductApplication
from agent_core.offering_application import OfferingApplication


def request(app, path, *, method="GET", body=None, cookie=None):
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

    db = connect_database(db_path)
    assert db.execute("SELECT COUNT(*) FROM sales").fetchone()[0] == 1
    assert db.execute("SELECT quantity_delta FROM inventory_movements").fetchone()[0] == -2
    db.close()
