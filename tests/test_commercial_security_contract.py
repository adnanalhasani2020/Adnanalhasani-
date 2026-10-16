"""Security-contract regression tests for the commercial web boundary."""

import io
import json

from agent_core.commercial_web import CommercialWebApplication


def call(app, path, *, method="GET", raw_body=b"", content_length=None, cookie=""):
    environ = {
        "REQUEST_METHOD": method,
        "PATH_INFO": path,
        "QUERY_STRING": "",
        "CONTENT_LENGTH": str(len(raw_body) if content_length is None else content_length),
        "CONTENT_TYPE": "application/json",
        "wsgi.input": io.BytesIO(raw_body),
        "HTTP_COOKIE": cookie,
        "SERVER_NAME": "localhost",
        "SERVER_PORT": "8080",
        "wsgi.url_scheme": "http",
    }
    captured = {}

    def start_response(status, headers):
        captured["status"] = status
        captured["headers"] = dict(headers)

    captured["body"] = b"".join(app(environ, start_response))
    return captured


def test_json_responses_are_non_cacheable_and_protected_by_baseline_headers(tmp_path):
    app = CommercialWebApplication(tmp_path / "security-headers.sqlite")
    response = call(app, "/api/me")

    assert response["status"] == "401 Unauthorized"
    assert response["headers"]["Cache-Control"] == "no-store"
    assert response["headers"]["X-Content-Type-Options"] == "nosniff"
    assert response["headers"]["Referrer-Policy"] == "same-origin"


def test_login_cookie_is_http_only_and_same_site(tmp_path):
    app = CommercialWebApplication(tmp_path / "security-cookie.sqlite")
    response = call(
        app,
        "/api/register",
        method="POST",
        raw_body=json.dumps({
            "username": "security_test_user",
            "password": "a sufficiently long test password",
        }).encode("utf-8"),
    )

    assert response["status"] == "200 OK"
    cookie = response["headers"]["Set-Cookie"]
    assert "HttpOnly" in cookie
    assert "SameSite=Lax" in cookie
    assert "Path=/" in cookie
    assert "Max-Age=28800" in cookie


def test_malformed_json_is_rejected_without_internal_error(tmp_path):
    app = CommercialWebApplication(tmp_path / "security-json.sqlite")
    response = call(
        app, "/api/login", method="POST", raw_body=b'{"username":',
    )

    assert response["status"] == "400 Bad Request"
    assert b"valid JSON" in response["body"]


def test_oversized_request_body_is_rejected_before_reading_payload(tmp_path):
    app = CommercialWebApplication(tmp_path / "security-size.sqlite")
    response = call(
        app, "/api/login", method="POST", raw_body=b"", content_length=65537,
    )

    assert response["status"] == "400 Bad Request"
    assert b"too large" in response["body"]
