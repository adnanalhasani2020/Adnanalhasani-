from __future__ import annotations

import io
import json
import os
from http.cookies import SimpleCookie
from unittest.mock import patch

from agent_core.commercial_web import CommercialWebApplication
from agent_core.persistence import connect_database


def request(app, path, *, method="GET", body=None, cookie=None):
    raw = json.dumps(body or {}).encode("utf-8")
    environ = {
        "REQUEST_METHOD": method,
        "PATH_INFO": path,
        "wsgi.url_scheme": "https",
        "HTTP_HOST": "example.test",
        "CONTENT_TYPE": "application/json",
        "CONTENT_LENGTH": str(len(raw)),
        "wsgi.input": io.BytesIO(raw),
    }
    if cookie:
        environ["HTTP_COOKIE"] = cookie
    captured = {}

    def start_response(status, headers):
        captured["status"] = status
        captured["headers"] = dict(headers)

    result = b"".join(app(environ, start_response))
    try:
        payload = json.loads(result.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        payload = result.decode("utf-8")
    return captured, payload


def enable_supabase(monkeypatch):
    monkeypatch.setenv("SUPABASE_URL", "https://example.supabase.co")
    monkeypatch.setenv("SUPABASE_ANON_KEY", "test-public-key")


def cookie_from(response):
    cookie = SimpleCookie()
    cookie.load(response["headers"]["Set-Cookie"])
    return "commercial_session=" + cookie["commercial_session"].value


def test_supabase_signup_creates_external_identity_and_commerce_session(tmp_path, monkeypatch):
    enable_supabase(monkeypatch)
    app = CommercialWebApplication(tmp_path / "commerce.sqlite")
    user = {"id": "supabase-user-001", "email": "buyer@example.com"}

    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class:
        auth_class.return_value.sign_up.return_value = {
            "user": user, "access_token": "external-access-token",
        }
        response, payload = request(
            app, "/api/register", method="POST",
            body={"email": "Buyer@example.com", "password": "correct horse battery staple"},
        )

    assert response["status"] == "200 OK"
    assert payload["username"] == "buyer@example.com"
    session_cookie = cookie_from(response)

    me_response, me = request(app, "/api/me", cookie=session_cookie)
    assert me_response["status"] == "200 OK"
    assert me["username"] == "buyer@example.com"

    db = connect_database(tmp_path / "commerce.sqlite")
    link = db.execute(
        "SELECT supabase_user_id,email FROM commercial_supabase_identities"
    ).fetchone()
    assert link == ("supabase-user-001", "buyer@example.com")
    assert db.execute("SELECT COUNT(*) FROM commercial_credentials").fetchone()[0] == 1
    db.close()


def test_supabase_login_reuses_local_commerce_profile(tmp_path, monkeypatch):
    enable_supabase(monkeypatch)
    db_path = tmp_path / "commerce.sqlite"
    app = CommercialWebApplication(db_path)
    user = {"id": "supabase-user-002", "email": "buyer@example.com"}

    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class:
        auth_class.return_value.sign_in_with_password.return_value = {
            "user": user, "access_token": "external-access-token",
        }
        first_response, first = request(
            app, "/api/login", method="POST",
            body={"email": "buyer@example.com", "password": "correct horse battery staple"},
        )
        second_response, second = request(
            app, "/api/login", method="POST",
            body={"email": "buyer@example.com", "password": "correct horse battery staple"},
        )

    assert first_response["status"] == second_response["status"] == "200 OK"
    assert first["person_id"] == second["person_id"]
    db = connect_database(db_path)
    assert db.execute("SELECT COUNT(*) FROM commercial_supabase_identities").fetchone()[0] == 1
    assert db.execute("SELECT COUNT(*) FROM persons").fetchone()[0] == 1
    db.close()


def test_supabase_signup_with_email_confirmation_does_not_issue_local_session(tmp_path, monkeypatch):
    enable_supabase(monkeypatch)
    app = CommercialWebApplication(tmp_path / "commerce.sqlite")

    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class:
        auth_class.return_value.sign_up.return_value = {
            "user": {"id": "pending-user", "email": "pending@example.com"},
            "access_token": None,
        }
        response, payload = request(
            app, "/api/register", method="POST",
            body={"email": "pending@example.com", "password": "correct horse battery staple"},
        )

    assert response["status"] == "202 Accepted"
    assert payload["needs_email_confirmation"] is True
    assert "Set-Cookie" not in response["headers"]
    db = connect_database(tmp_path / "commerce.sqlite")
    assert db.execute("SELECT COUNT(*) FROM commercial_supabase_identities").fetchone()[0] == 0
    db.close()


def test_supabase_auth_ui_uses_email_fields_when_configured(monkeypatch):
    enable_supabase(monkeypatch)
    app = CommercialWebApplication()
    response, page = request(app, "/")
    assert response["status"] == "200 OK"
    assert 'id="loginEmail" name="email" type="email"' in page
    assert 'id="registerEmail" name="email" type="email"' in page
    assert 'id="loginUsername"' not in page
