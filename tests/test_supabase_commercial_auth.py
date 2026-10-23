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
    assert 'id="storeSetupForm"' in page
    assert 'name="activity_type"' in page
    assert 'name="business_level"' in page
    assert 'name="commercial_registration_number"' in page
    assert 'name="owner_full_name"' in page
    assert 'name="opened_on"' in page
    assert 'id="storeLogoUrl" name="logo_url" type="url"' in page
    assert 'id="storeCoverUrl" name="cover_image_url" type="url"' in page
    assert 'id="ownerIdentityFrontUrl" name="owner_identity_front_url" type="url"' in page
    assert 'id="ownerIdentityBackUrl" name="owner_identity_back_url" type="url"' in page
    assert 'type="file"' not in page
    assert "/api/supabase/profile-assets" not in page
    assert 'id="locationRows"' in page
    assert 'firstProductName' not in page
    assert 'initialQuantity' not in page


def test_storefront_api_requires_authenticated_supabase_session(tmp_path, monkeypatch):
    enable_supabase(monkeypatch)
    app = CommercialWebApplication(tmp_path / "commerce.sqlite")
    response, payload = request(app, "/api/supabase/storefronts", method="POST",
                                body={"organization_name": "متجر النور"})
    assert response["status"] == "401 Unauthorized"
    assert "Supabase" in payload["error"]


def test_storefront_api_uses_server_side_supabase_token(tmp_path, monkeypatch):
    enable_supabase(monkeypatch)
    app = CommercialWebApplication(tmp_path / "commerce.sqlite")
    user = {"id": "store-owner-001", "email": "owner@example.com"}
    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class:
        auth_class.return_value.sign_in_with_password.return_value = {
            "user": user, "access_token": "server-only-user-token",
        }
        login_response, _ = request(app, "/api/login", method="POST",
                                    body={"email": user["email"], "password": "correct horse battery staple"})
    session_cookie = cookie_from(login_response)
    expected = {"organization_id": "org-1", "activity_id": "activity-1",
                "location_id": "location-1", "product_id": "product-1",
                "offering_id": "offering-1", "initial_quantity": 7, "currency_code": "YER"}
    with patch("agent_core.commercial_web.SupabaseRPCClient") as client_class:
        client_class.return_value.create_storefront.return_value = expected
        response, payload = request(
            app, "/api/supabase/storefronts", method="POST", cookie=session_cookie,
            body={"organization_name": "متجر النور", "organization_slug": "alnoor-store",
                  "activity_name": "مواد غذائية", "product_name": "أرز", "unit_name": "كيس",
                  "price_minor": 500, "currency_code": "YER", "location_name": "الفرع الرئيسي",
                  "initial_quantity": 7, "country_code": "YE", "timezone": "Asia/Aden",
                  "locale": "ar", "public_listing": False},
        )
    assert response["status"] == "201 Created"
    assert payload == expected
    client_class.assert_called_once_with("server-only-user-token")
    assert "server-only-user-token" not in json.dumps(payload)


def test_logout_clears_server_side_supabase_token(tmp_path, monkeypatch):
    enable_supabase(monkeypatch)
    app = CommercialWebApplication(tmp_path / "commerce.sqlite")
    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class:
        auth_class.return_value.sign_in_with_password.return_value = {
            "user": {"id": "store-owner-002", "email": "owner2@example.com"},
            "access_token": "logout-token",
        }
        response, _ = request(app, "/api/login", method="POST",
                               body={"email": "owner2@example.com", "password": "correct horse battery staple"})
    cookie = cookie_from(response)
    session = cookie.removeprefix("commercial_session=")
    assert session in app._supabase_tokens
    request(app, "/api/logout", method="POST", cookie=cookie)
    assert session not in app._supabase_tokens


def test_profile_api_requires_authenticated_supabase_session(tmp_path, monkeypatch):
    enable_supabase(monkeypatch)
    app = CommercialWebApplication(tmp_path / "commerce.sqlite")
    response, payload = request(app, "/api/supabase/store-profiles", method="POST",
                                body={"organization_name": "متجر النور"})
    assert response["status"] == "401 Unauthorized"
    assert "Supabase" in payload["error"]


def test_profile_api_persists_activity_owner_registration_and_hierarchy(tmp_path, monkeypatch):
    enable_supabase(monkeypatch)
    app = CommercialWebApplication(tmp_path / "commerce.sqlite")
    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class:
        auth_class.return_value.sign_in_with_password.return_value = {
            "user": {"id": "activity-owner-001", "email": "owner@example.com"},
            "access_token": "profile-owner-token",
        }
        login_response, _ = request(app, "/api/login", method="POST",
                                    body={"email": "owner@example.com", "password": "correct horse battery staple"})
    expected = {"organization_id": "org-profile-1", "activity_id": "activity-profile-1",
                "location_id": "hq-1", "location_count": 3, "commercial_registration_number": "CR-001"}
    with patch("agent_core.commercial_web.SupabaseRPCClient") as client_class:
        client_class.return_value.create_activity_profile.return_value = expected
        response, payload = request(
            app, "/api/supabase/store-profiles", method="POST", cookie=cookie_from(login_response),
            body={"organization_name": "مؤسسة النور", "organization_slug": "alnoor-org",
                  "activity_name": "خدمات تقنية", "activity_type": "service", "business_level": "institution",
                  "commercial_registration_number": "CR-001", "opened_on": "2024-05-10",
                  "owner_full_name": "مالك النشاط", "legal_name": "مؤسسة النور",
                  "country_code": "YE", "timezone": "Asia/Aden", "locale": "ar",
                  "currency_code": "YER", "logo_url": "https://images.example.com/logo.png",
                  "cover_image_url": "https://images.example.com/cover.png",
                  "owner_identity_front_url": "https://images.example.com/front.png",
                  "owner_identity_back_url": "https://images.example.com/back.png",
                  "locations": [
                      {"name": "القناة الأولى", "location_type": "office", "parent_index": -1},
                      {"name": "فرع القناة الأولى", "location_type": "branch", "parent_index": 0},
                  ]},
        )
    assert response["status"] == "201 Created"
    assert payload == expected
    client_class.assert_called_once_with("profile-owner-token")
    args = client_class.return_value.create_activity_profile.call_args.kwargs
    assert args["commercial_registration_number"] == "CR-001"
    assert args["opened_on"] == "2024-05-10"
    assert args["logo_url"] == "https://images.example.com/logo.png"
    assert args["cover_image_url"] == "https://images.example.com/cover.png"
    assert args["owner_identity_front_url"] == "https://images.example.com/front.png"
    assert args["owner_identity_back_url"] == "https://images.example.com/back.png"
    assert args["locations"][1]["parent_index"] == 0
