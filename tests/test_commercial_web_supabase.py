from __future__ import annotations

import io
import json
from unittest.mock import patch

from agent_core.commercial_web import CommercialWebApplication


def invoke(app, body, *, authorization=""):
    raw = json.dumps(body).encode("utf-8")
    environ = {
        "REQUEST_METHOD": "POST",
        "PATH_INFO": "/api/supabase/organizations",
        "wsgi.url_scheme": "https",
        "HTTP_HOST": "example.test",
        "HTTP_AUTHORIZATION": authorization,
        "CONTENT_TYPE": "application/json",
        "CONTENT_LENGTH": str(len(raw)),
        "wsgi.input": io.BytesIO(raw),
    }
    captured = {}
    def start_response(status, headers):
        captured["status"] = status
        captured["headers"] = dict(headers)
    result = b"".join(app(environ, start_response))
    return captured, json.loads(result.decode("utf-8"))


def test_supabase_onboarding_requires_bearer_token():
    response, body = invoke(CommercialWebApplication(), {"name": "Org"})
    assert response["status"] == "401 Unauthorized"
    assert "رمز وصول" in body["error"]


def test_supabase_onboarding_calls_atomic_rpc_with_user_token():
    app = CommercialWebApplication()
    expected = "00000000-0000-0000-0000-000000000001"
    with patch("agent_core.commercial_web.SupabaseRPCClient") as client_class:
        client_class.return_value.create_organization.return_value = expected
        response, body = invoke(
            app,
            {
                "name": "متجر تجريبي", "slug": "demo-store",
                "organization_type": "retail", "currency_code": "YER",
                "timezone": "Asia/Aden", "locale": "ar", "country_code": "YE",
                "module_keys": ["core_operations"],
            },
            authorization="Bearer user-jwt-example",
        )
    assert response["status"] == "201 Created"
    assert body == {"organization_id": expected}
    client_class.assert_called_once_with("user-jwt-example")
    client_class.return_value.create_organization.assert_called_once_with(
        name="متجر تجريبي", slug="demo-store", organization_type="retail",
        currency_code="YER", timezone="Asia/Aden", locale="ar",
        country_code="YE", module_keys=["core_operations"],
    )


def test_supabase_catalog_requires_session_cookie():
    app = CommercialWebApplication()
    environ = {
        "REQUEST_METHOD": "GET", "PATH_INFO": "/api/supabase/catalog",
        "wsgi.url_scheme": "https", "HTTP_HOST": "example.test",
        "HTTP_COOKIE": "", "CONTENT_LENGTH": "0", "wsgi.input": io.BytesIO(b""),
    }
    captured = {}
    def start_response(status, headers):
        captured["status"] = status
    result = b"".join(app(environ, start_response))
    assert captured["status"] == "401 Unauthorized"
    assert "Supabase" in json.loads(result.decode("utf-8"))["error"]


def test_supabase_catalog_validates_session_and_uses_user_token():
    app = CommercialWebApplication()
    environ = {
        "REQUEST_METHOD": "GET", "PATH_INFO": "/api/supabase/catalog",
        "wsgi.url_scheme": "https", "HTTP_HOST": "example.test",
        "HTTP_COOKIE": "supabase_access=access-token; supabase_refresh=refresh-token",
        "CONTENT_LENGTH": "0", "wsgi.input": io.BytesIO(b""),
    }
    captured = {}
    def start_response(status, headers):
        captured["status"], captured["headers"] = status, headers
    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class, \
         patch("agent_core.commercial_web.SupabaseRPCClient") as rpc_class:
        auth_class.return_value.get_user.return_value = {"id": "user-1", "email": "user@example.test"}
        rpc_class.return_value.catalog.return_value = [{"offering_id": "offer-1"}]
        result = b"".join(app(environ, start_response))
    assert captured["status"] == "200 OK"
    assert json.loads(result.decode("utf-8")) == {"items": [{"offering_id": "offer-1"}]}
    auth_class.return_value.get_user.assert_called_once_with("access-token")
    rpc_class.assert_called_once_with("access-token")


def test_supabase_checkout_requires_auth_and_calls_atomic_rpc():
    app = CommercialWebApplication()
    body = {"offering_id": "offer-uuid", "location_id": "location-uuid",
            "quantity": 2, "idempotency_key": "checkout-key-123"}
    raw = json.dumps(body).encode()
    environ = {
        "REQUEST_METHOD": "POST", "PATH_INFO": "/api/supabase/checkout",
        "wsgi.url_scheme": "https", "HTTP_HOST": "example.test",
        "HTTP_COOKIE": "supabase_access=access-token; supabase_refresh=refresh-token",
        "CONTENT_TYPE": "application/json", "CONTENT_LENGTH": str(len(raw)),
        "wsgi.input": io.BytesIO(raw),
    }
    captured = {}
    def start_response(status, headers):
        captured["status"], captured["headers"] = status, headers
    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class, \
         patch("agent_core.commercial_web.SupabaseRPCClient") as rpc_class:
        auth_class.return_value.get_user.return_value = {"id": "user-1"}
        rpc_class.return_value.checkout.return_value = {
            "sale_id": "sale-uuid", "total_minor": 2500, "idempotent_replay": False
        }
        result = b"".join(app(environ, start_response))
    assert captured["status"] == "201 Created"
    assert json.loads(result.decode())["sale_id"] == "sale-uuid"
    rpc_class.assert_called_once_with("access-token")
    rpc_class.return_value.checkout.assert_called_once_with(
        offering_id="offer-uuid", location_id="location-uuid",
        quantity=2, idempotency_key="checkout-key-123",
    )


def test_supabase_receipts_requires_auth_and_returns_owned_rows():
    app = CommercialWebApplication()
    environ = {
        "REQUEST_METHOD": "GET", "PATH_INFO": "/api/supabase/receipts",
        "wsgi.url_scheme": "https", "HTTP_HOST": "example.test",
        "HTTP_COOKIE": "supabase_access=access-token", "CONTENT_LENGTH": "0",
        "wsgi.input": io.BytesIO(b""),
    }
    captured = {}
    def start_response(status, headers):
        captured["status"] = status
    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class, \
         patch("agent_core.commercial_web.SupabaseRPCClient") as rpc_class:
        auth_class.return_value.get_user.return_value = {"id": "user-1"}
        rpc_class.return_value.receipts.return_value = [{"sale_id": "sale-uuid"}]
        result = b"".join(app(environ, start_response))
    assert captured["status"] == "200 OK"
    assert json.loads(result.decode()) == {"items": [{"sale_id": "sale-uuid"}]}


def test_commercial_page_uses_supabase_email_auth_and_commerce_routes():
    page = CommercialWebApplication()
    environ = {"REQUEST_METHOD": "GET", "PATH_INFO": "/", "wsgi.url_scheme": "https",
               "HTTP_HOST": "example.test"}
    captured = {}
    def start_response(status, headers):
        captured["status"], captured["headers"] = status, headers
    body = b"".join(page(environ, start_response)).decode("utf-8")
    assert captured["status"] == "200 OK"
    assert 'id="loginEmail" name="email" type="email"' in body
    assert 'id="registerEmail" name="email" type="email"' in body
    assert "/api/supabase/auth/login" in body
    assert "/api/supabase/auth/signup" in body
    assert "/api/supabase/catalog" in body
    assert "/api/supabase/checkout" in body
    assert "/api/supabase/receipts" in body
    assert "api('/api/login'" not in body
    assert "api('/api/register'" not in body


def test_supabase_printable_receipt_uses_authenticated_sale_data_and_escapes_html():
    app = CommercialWebApplication()
    environ = {
        "REQUEST_METHOD": "GET", "PATH_INFO": "/supabase/receipts/sale-uuid",
        "wsgi.url_scheme": "https", "HTTP_HOST": "example.test",
        "HTTP_COOKIE": "supabase_access=access-token", "CONTENT_LENGTH": "0",
        "wsgi.input": io.BytesIO(b""),
    }
    captured = {}
    def start_response(status, headers):
        captured["status"], captured["headers"] = status, headers
    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class, \
         patch("agent_core.commercial_web.SupabaseRPCClient") as rpc_class:
        auth_class.return_value.get_user.return_value = {"id": "user-1"}
        rpc_class.return_value.receipts.return_value = [{
            "sale_id": "sale-uuid", "receipt_number": 42, "product_name": "<script>alert(1)</script>",
            "quantity": 2, "unit_price_minor": 100, "total_minor": 200, "currency_code": "YER",
            "state": "completed", "occurred_at": "2026-10-23T10:00:00Z",
        }]
        body = b"".join(app(environ, start_response)).decode("utf-8")
    assert captured["status"] == "200 OK"
    assert "إيصال بيع" in body
    assert "&lt;script&gt;" in body
    assert "<script>alert(1)</script>" not in body
    assert "window.print()" in body


def test_supabase_storefront_setup_requires_auth_and_calls_atomic_rpc():
    app = CommercialWebApplication()
    body = {
        "organization_name": "متجر الروح", "organization_slug": "rooh-store",
        "activity_name": "المتجر الرئيسي", "product_name": "ماء",
        "unit_name": "عبوة", "price_minor": 1500, "currency_code": "YER",
        "location_name": "الفرع الرئيسي", "initial_quantity": 12,
    }
    raw = json.dumps(body).encode()
    environ = {
        "REQUEST_METHOD": "POST", "PATH_INFO": "/api/supabase/storefront",
        "wsgi.url_scheme": "https", "HTTP_HOST": "example.test",
        "HTTP_COOKIE": "supabase_access=access-token", "CONTENT_TYPE": "application/json",
        "CONTENT_LENGTH": str(len(raw)), "wsgi.input": io.BytesIO(raw),
    }
    captured = {}
    def start_response(status, headers):
        captured["status"] = status
    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class, \
         patch("agent_core.commercial_web.SupabaseRPCClient") as rpc_class:
        auth_class.return_value.get_user.return_value = {"id": "user-1"}
        rpc_class.return_value.create_storefront.return_value = {
            "organization_id": "org-uuid", "offering_id": "offer-uuid"
        }
        result = b"".join(app(environ, start_response))
    assert captured["status"] == "201 Created"
    assert json.loads(result.decode())["organization_id"] == "org-uuid"
    rpc_class.return_value.create_storefront.assert_called_once_with(
        organization_name="متجر الروح", organization_slug="rooh-store",
        activity_name="المتجر الرئيسي", product_name="ماء", unit_name="عبوة",
        price_minor=1500, currency_code="YER", location_name="الفرع الرئيسي",
        initial_quantity=12,
    )


def test_supabase_add_store_item_requires_auth_and_uses_catalog_context():
    app = CommercialWebApplication()
    body = {
        "organization_id": "org-uuid", "activity_id": "activity-uuid",
        "location_id": "location-uuid", "product_name": "Water",
        "unit_name": "bottle", "price_minor": 1500, "initial_quantity": 8,
    }
    raw = json.dumps(body).encode()
    environ = {
        "REQUEST_METHOD": "POST", "PATH_INFO": "/api/supabase/store-items",
        "wsgi.url_scheme": "https", "HTTP_HOST": "example.test",
        "HTTP_COOKIE": "supabase_access=access-token", "CONTENT_TYPE": "application/json",
        "CONTENT_LENGTH": str(len(raw)), "wsgi.input": io.BytesIO(raw),
    }
    captured = {}
    def start_response(status, headers):
        captured["status"] = status
    with patch("agent_core.commercial_web.SupabaseAuthClient") as auth_class, \
         patch("agent_core.commercial_web.SupabaseRPCClient") as rpc_class:
        auth_class.return_value.get_user.return_value = {"id": "user-1"}
        rpc_class.return_value.add_store_item.return_value = {"offering_id": "offer-uuid"}
        result = b"".join(app(environ, start_response))
    assert captured["status"] == "201 Created"
    assert json.loads(result.decode())["offering_id"] == "offer-uuid"
    rpc_class.return_value.add_store_item.assert_called_once_with(
        organization_id="org-uuid", activity_id="activity-uuid",
        location_id="location-uuid", product_name="Water", unit_name="bottle",
        price_minor=1500, initial_quantity=8,
    )
