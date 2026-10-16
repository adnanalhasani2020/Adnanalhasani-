import io
import json
from http.cookies import SimpleCookie

from agent_core.commercial_web import CommercialWebApplication
from agent_core.persistence import connect_database


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
