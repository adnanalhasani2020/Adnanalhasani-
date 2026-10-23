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
